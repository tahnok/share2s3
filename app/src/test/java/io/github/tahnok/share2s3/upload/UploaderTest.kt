package io.github.tahnok.share2s3.upload

import io.github.tahnok.share2s3.s3.S3Config
import io.github.tahnok.share2s3.s3.S3Exception
import io.github.tahnok.share2s3.s3.SigV4Signer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class UploaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        server.start()
        settings = AppSettings(
            s3 = S3Config(
                endpoint = server.url("/").toString(),
                region = "garage",
                bucket = "bucket",
                accessKey = "key",
                secretKey = "secret",
            ),
            keyTemplate = "uploads/{filename}",
            publicUrlTemplate = "https://files.example.com/{key}",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source(name: String, content: String, mime: String? = "text/plain") =
        UploadSource(name, mime) { content.byteInputStream() }

    @Test
    fun `uploads each source and returns public urls`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val cache = tmp.newFolder()
        val progress = mutableListOf<UploadProgress>()

        val results = Uploader(settings, cache).uploadAll(
            listOf(source("a.txt", "first"), source("b c.png", "second", "image/png")),
        ) { progress += it }

        assertEquals(
            listOf(
                UploadResult("uploads/a.txt", "https://files.example.com/uploads/a.txt"),
                UploadResult("uploads/b_c.png", "https://files.example.com/uploads/b_c.png"),
            ),
            results,
        )

        val first = server.takeRequest()
        assertEquals("/bucket/uploads/a.txt", first.path)
        assertEquals("first", first.body.readUtf8())
        assertEquals(SigV4Signer.sha256Hex("first".toByteArray()), first.getHeader("x-amz-content-sha256"))

        val second = server.takeRequest()
        assertEquals("/bucket/uploads/b_c.png", second.path)
        assertEquals("image/png", second.getHeader("Content-Type"))

        assertEquals(UploadProgress(1, 2, "b c.png", 6, 6), progress.last())
        assertTrue("staged files are cleaned up", cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `failures propagate and clean up`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val cache = tmp.newFolder()

        assertThrows(S3Exception::class.java) {
            Uploader(settings, cache).upload(source("a.txt", "x"))
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `unreadable source fails before contacting the server`() {
        val cache = tmp.newFolder()
        val broken = UploadSource("a.txt", null) { throw IOException("gone") }

        assertThrows(IOException::class.java) { Uploader(settings, cache).upload(broken) }
        assertEquals(0, server.requestCount)
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
