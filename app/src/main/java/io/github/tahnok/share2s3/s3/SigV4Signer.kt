package io.github.tahnok.share2s3.s3

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 request signer, as used by S3 and S3-compatible services like Garage.
 *
 * See https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 */
class SigV4Signer(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
) {
    /**
     * Returns the value of the `Authorization` header for a request.
     *
     * @param canonicalUri the already URI-encoded request path, e.g. `/bucket/my%20file.txt`
     * @param query decoded query parameters
     * @param headers every header that should be signed; must include `host`, `x-amz-date` and
     *   `x-amz-content-sha256`
     * @param payloadHash hex SHA-256 of the body, or `UNSIGNED-PAYLOAD`
     */
    fun authorization(
        method: String,
        canonicalUri: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        payloadHash: String,
        time: Instant,
    ): String {
        val amzDate = AMZ_DATE.format(time)
        val date = amzDate.substring(0, 8)
        val scope = "$date/$region/$service/aws4_request"

        val sortedHeaders = headers.entries
            .map { it.key.lowercase() to it.value.trim().replace(WHITESPACE, " ") }
            .sortedBy { it.first }
        val signedHeaders = sortedHeaders.joinToString(";") { it.first }
        val canonicalHeaders = sortedHeaders.joinToString("") { "${it.first}:${it.second}\n" }
        val canonicalQuery = query.entries
            .map { uriEncode(it.key) to uriEncode(it.value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }

        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray()),
        ).joinToString("\n")

        var key = hmac("AWS4$secretKey".toByteArray(), date)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hmac(key, stringToSign).toHex()

        return "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val AMZ_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private val WHITESPACE = Regex("\\s+")

        /** RFC 3986 encoding as required by SigV4: everything except unreserved characters. */
        fun uriEncode(value: String, encodeSlash: Boolean = true): String {
            val sb = StringBuilder()
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                        c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                    c == '/' && !encodeSlash -> sb.append(c)
                    else -> sb.append('%').append(HEX_UPPER[c.code shr 4]).append(HEX_UPPER[c.code and 0xf])
                }
            }
            return sb.toString()
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun hmac(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        private const val HEX_UPPER = "0123456789ABCDEF"
        private const val HEX_LOWER = "0123456789abcdef"

        fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xff
                sb.append(HEX_LOWER[v shr 4]).append(HEX_LOWER[v and 0xf])
            }
            return sb.toString()
        }
    }
}
