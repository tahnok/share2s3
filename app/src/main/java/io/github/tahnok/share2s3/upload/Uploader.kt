package io.github.tahnok.share2s3.upload

import io.github.tahnok.share2s3.s3.S3Client
import io.github.tahnok.share2s3.s3.SigV4Signer.Companion.toHex
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock

/** Something shared with the app that can be uploaded. */
class UploadSource(
    val filename: String,
    val mimeType: String?,
    val open: () -> InputStream,
)

data class UploadResult(val key: String, val url: String)

/** Progress of the current batch: file [index] of [count], [bytesSent] of [totalBytes]. */
data class UploadProgress(val index: Int, val count: Int, val filename: String, val bytesSent: Long, val totalBytes: Long)

class Uploader(
    private val settings: AppSettings,
    private val cacheDir: File,
    private val client: S3Client = S3Client(settings.s3),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Uploads every source in order, returning the resulting URLs. Stops at the first failure. */
    fun uploadAll(sources: List<UploadSource>, onProgress: (UploadProgress) -> Unit = {}): List<UploadResult> =
        sources.mapIndexed { index, source ->
            upload(source) { sent, total ->
                onProgress(UploadProgress(index, sources.size, source.filename, sent, total))
            }
        }

    fun upload(source: UploadSource, onProgress: (Long, Long) -> Unit = { _, _ -> }): UploadResult {
        // S3 needs the content length and payload hash before sending, and the content URI we were
        // handed may not be re-readable, so stage the data in the cache first.
        val staged = File.createTempFile("upload", ".tmp", cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.open().use { input ->
                DigestOutputStream(staged.outputStream(), digest).use { input.copyTo(it) }
            }
            val total = staged.length()
            onProgress(0, total)

            val key = KeyTemplate.render(settings.keyTemplate, source.filename, clock.instant())
            client.putObject(key, staged, digest.digest().toHex(), source.mimeType) { sent ->
                onProgress(sent, total)
            }
            return UploadResult(key, PublicUrlTemplate.render(settings.publicUrlTemplate, settings.s3, key))
        } finally {
            staged.delete()
        }
    }
}
