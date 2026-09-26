package io.github.tahnok.share2s3.s3

import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit

class S3Exception(val statusCode: Int, val errorCode: String?, message: String) : IOException(message)

/** Minimal S3 client covering what the app needs: uploading objects and checking a bucket. */
class S3Client(
    private val config: S3Config,
    private val http: OkHttpClient = defaultHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val signer = SigV4Signer(config.accessKey, config.secretKey, config.region)

    /**
     * Uploads [file] as [key].
     *
     * @param sha256 hex SHA-256 of the file contents, used to sign the payload
     * @param onProgress called with the number of bytes sent so far
     */
    fun putObject(
        key: String,
        file: File,
        sha256: String,
        contentType: String?,
        onProgress: (Long) -> Unit = {},
    ) {
        val url = config.objectUrl(key)
        val body = ProgressFileBody(file, contentType?.toMediaTypeOrNull(), onProgress)
        execute("PUT", url, sha256, body).use { }
    }

    /** Checks the bucket exists and the credentials may access it. */
    fun headBucket() {
        execute("HEAD", config.bucketUrl(), SigV4Signer.EMPTY_SHA256, null).use { }
    }

    private fun execute(method: String, url: HttpUrl, payloadHash: String, body: RequestBody?): Response {
        val now = clock.instant()
        val signedHeaders = mapOf(
            "host" to hostHeader(url),
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to SigV4Signer.AMZ_DATE.format(now),
        )
        val query = url.queryParameterNames.associateWith { url.queryParameter(it) ?: "" }
        val authorization = signer.authorization(method, url.encodedPath, query, signedHeaders, payloadHash, now)

        val request = Request.Builder()
            .url(url)
            .method(method, body)
            .apply { signedHeaders.forEach { (k, v) -> header(k, v) } }
            .header("Authorization", authorization)
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.use { throw toException(it) }
        }
        return response
    }

    private fun toException(response: Response): S3Exception {
        val xml = response.body?.string().orEmpty()
        val code = XML_CODE.find(xml)?.groupValues?.get(1)
        val message = XML_MESSAGE.find(xml)?.groupValues?.get(1)
        val description = buildString {
            append("HTTP ").append(response.code)
            if (code != null) append(" ").append(code)
            if (!message.isNullOrBlank()) append(": ").append(message)
            else if (code == null) describeStatus(response.code)?.let { append(": ").append(it) }
        }
        return S3Exception(response.code, code, description)
    }

    private class ProgressFileBody(
        private val file: File,
        private val contentType: MediaType?,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = contentType
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            file.source().use { source ->
                var total = 0L
                while (true) {
                    val read = source.read(sink.buffer, 64 * 1024L)
                    if (read == -1L) break
                    total += read
                    sink.flush()
                    onProgress(total)
                }
            }
        }
    }

    companion object {
        private val XML_CODE = Regex("<Code>(.*?)</Code>", RegexOption.DOT_MATCHES_ALL)
        private val XML_MESSAGE = Regex("<Message>(.*?)</Message>", RegexOption.DOT_MATCHES_ALL)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** The Host header exactly as OkHttp will send it; it must match what was signed. */
        fun hostHeader(url: HttpUrl): String {
            val host = if (url.host.contains(':')) "[${url.host}]" else url.host
            return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
        }

        private fun describeStatus(code: Int): String? = when (code) {
            400 -> "Bad request (check the region setting)"
            403 -> "Access denied (check the keys and bucket permissions)"
            404 -> "Bucket not found"
            else -> null
        }
    }
}
