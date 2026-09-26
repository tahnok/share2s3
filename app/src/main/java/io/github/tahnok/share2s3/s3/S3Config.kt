package io.github.tahnok.share2s3.s3

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Connection details for an S3-compatible bucket. */
data class S3Config(
    /** Base URL of the S3 API, e.g. `https://s3.example.com` or `http://192.168.1.2:3900`. */
    val endpoint: String,
    /** Garage uses `garage` by default. */
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    /**
     * `true` for `https://endpoint/bucket/key` (Garage's default), `false` for
     * `https://bucket.endpoint/key` (requires `root_domain` to be configured in Garage).
     */
    val pathStyle: Boolean = true,
) {
    /** Returns a list of human-readable problems, empty when the config is usable. */
    fun validate(): List<String> = buildList {
        if (endpoint.isBlank()) {
            add("Endpoint is not set")
        } else if (parsedEndpoint() == null) {
            add("Endpoint must be a full URL starting with http:// or https://")
        }
        if (region.isBlank()) add("Region is not set")
        if (bucket.isBlank()) add("Bucket is not set")
        if (accessKey.isBlank()) add("Access key ID is not set")
        if (secretKey.isBlank()) add("Secret key is not set")
    }

    fun parsedEndpoint(): HttpUrl? = endpoint.trim().toHttpUrlOrNull()

    /** URL of the object with the given (unencoded) key. */
    fun objectUrl(key: String): HttpUrl = buildUrl(encodeKey(key))

    /** URL of the bucket itself. */
    fun bucketUrl(): HttpUrl = buildUrl("")

    private fun buildUrl(encodedKey: String): HttpUrl {
        val base = requireNotNull(parsedEndpoint()) { "Invalid endpoint: $endpoint" }
        // Keep any path prefix on the endpoint (e.g. behind a reverse proxy), without a trailing slash.
        val prefix = base.encodedPath.trimEnd('/')
        val encodedBucket = SigV4Signer.uriEncode(bucket)
        return if (pathStyle) {
            val path = if (encodedKey.isEmpty()) "$prefix/$encodedBucket" else "$prefix/$encodedBucket/$encodedKey"
            base.newBuilder().encodedPath(path).build()
        } else {
            base.newBuilder()
                .host("$bucket.${base.host}")
                .encodedPath("$prefix/$encodedKey")
                .build()
        }
    }

    companion object {
        /** Encodes an object key for use in a URL path, leaving `/` separators intact. */
        fun encodeKey(key: String): String = SigV4Signer.uriEncode(key, encodeSlash = false)
    }
}
