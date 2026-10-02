package io.github.tahnok.share2s3.s3

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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class S3ClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val now = Instant.parse("2024-01-02T03:04:05Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var config: S3Config

    @Before
    fun setUp() {
        server.start()
        config = S3Config(
            endpoint = server.url("/").toString(),
            region = "garage",
            bucket = "my-bucket",
            accessKey = "GKexample",
            secretKey = "secretexample",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `putObject sends a correctly signed PUT`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val file = tmp.newFile().apply { writeText("hello world") }
        val sha = SigV4Signer.sha256Hex(file.readBytes())
        val progress = mutableListOf<Long>()

        S3Client(config, clock = clock).putObject("dir/hello world.txt", file, sha, "text/plain") { progress += it }

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/my-bucket/dir/hello%20world.txt", request.path)
        assertEquals("hello world", request.body.readUtf8())
        assertEquals("text/plain", request.getHeader("Content-Type"))
        assertEquals("11", request.getHeader("Content-Length"))
        assertEquals(sha, request.getHeader("x-amz-content-sha256"))
        assertEquals("20240102T030405Z", request.getHeader("x-amz-date"))
        assertEquals(11L, progress.last())

        // Re-derive the signature from what the server actually received.
        val expected = SigV4Signer("GKexample", "secretexample", "garage").authorization(
            method = "PUT",
            canonicalUri = request.requestUrl!!.encodedPath,
            query = emptyMap(),
            headers = mapOf(
                "host" to request.getHeader("Host")!!,
                "x-amz-content-sha256" to sha,
                "x-amz-date" to "20240102T030405Z",
            ),
            payloadHash = sha,
            time = now,
        )
        assertEquals(expected, request.getHeader("Authorization"))
        assertTrue(expected.contains("Credential=GKexample/20240102/garage/s3/aws4_request"))
    }

    @Test
    fun `headBucket sends HEAD to the bucket`() {
        server.enqueue(MockResponse().setResponseCode(200))

        S3Client(config, clock = clock).headBucket()

        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("/my-bucket", request.path)
        assertEquals(SigV4Signer.EMPTY_SHA256, request.getHeader("x-amz-content-sha256"))
    }

    @Test
    fun `S3 error responses are parsed`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """<?xml version="1.0" encoding="UTF-8"?>
                <Error><Code>AccessDenied</Code><Message>Forbidden: no write access</Message></Error>""",
            ),
        )
        val file = tmp.newFile().apply { writeText("x") }

        val e = assertThrows(S3Exception::class.java) {
            S3Client(config, clock = clock).putObject("x", file, SigV4Signer.sha256Hex(byteArrayOf('x'.code.toByte())), null)
        }
        assertEquals(403, e.statusCode)
        assertEquals("AccessDenied", e.errorCode)
        assertEquals("HTTP 403 AccessDenied: Forbidden: no write access", e.message)
    }

    @Test
    fun `errors without a body get a helpful message`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val e = assertThrows(S3Exception::class.java) { S3Client(config, clock = clock).headBucket() }
        assertEquals("HTTP 404: Bucket not found", e.message)
    }
}
