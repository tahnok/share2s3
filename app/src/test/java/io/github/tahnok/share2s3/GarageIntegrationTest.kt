package io.github.tahnok.share2s3

import io.github.tahnok.share2s3.s3.S3Client
import io.github.tahnok.share2s3.s3.S3Config
import io.github.tahnok.share2s3.s3.S3Exception
import io.github.tahnok.share2s3.upload.AppSettings
import io.github.tahnok.share2s3.upload.UploadSource
import io.github.tahnok.share2s3.upload.Uploader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * End-to-end tests against a real Garage server. Skipped unless the GARAGE_* environment variables
 * are set; `scripts/garage-test-server.sh` starts a suitable server.
 */
class GarageIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var s3: S3Config

    @Before
    fun setUp() {
        val endpoint = System.getenv("GARAGE_ENDPOINT")
        assumeTrue("GARAGE_ENDPOINT not set, skipping Garage integration tests", !endpoint.isNullOrEmpty())
        s3 = S3Config(
            endpoint = endpoint!!,
            region = "garage",
            bucket = System.getenv("GARAGE_BUCKET")!!,
            accessKey = System.getenv("GARAGE_ACCESS_KEY")!!,
            secretKey = System.getenv("GARAGE_SECRET_KEY")!!,
        )
    }

    @Test
    fun `head bucket succeeds with valid credentials`() {
        S3Client(s3).headBucket()
    }

    @Test
    fun `wrong secret is rejected`() {
        val e = assertThrows(S3Exception::class.java) {
            S3Client(s3.copy(secretKey = "0".repeat(64))).headBucket()
        }
        assertEquals(403, e.statusCode)
    }

    @Test
    fun `uploaded files are served by the Garage web endpoint`() {
        val webEndpoint = System.getenv("GARAGE_WEB_ENDPOINT")!!
        val webHost = s3.bucket + System.getenv("GARAGE_WEB_ROOT_DOMAIN")!!
        val settings = AppSettings(
            s3 = s3,
            keyTemplate = "{random}/{filename}",
            publicUrlTemplate = "$webEndpoint/{key}",
        )
        val content = Random.nextBytes(300_000)

        val result = Uploader(settings, tmp.newFolder()).upload(
            UploadSource("holiday photo €.jpg", "image/jpeg") { content.inputStream() },
        )

        // The web endpoint routes on the Host header, so the template points at it directly.
        val response = OkHttpClient().newCall(
            Request.Builder().url(result.url).header("Host", webHost).build(),
        ).execute()
        response.use {
            assertEquals(200, it.code)
            assertEquals("image/jpeg", it.header("Content-Type"))
            assertEquals(content.toList(), it.body!!.bytes().toList())
        }
    }
}
