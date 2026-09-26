package io.github.tahnok.share2s3.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S3ConfigTest {
    private val config = S3Config(
        endpoint = "https://s3.example.com",
        region = "garage",
        bucket = "files",
        accessKey = "GK123",
        secretKey = "secret",
    )

    @Test
    fun `path style object url`() {
        assertEquals(
            "https://s3.example.com/files/abc/my%20photo.jpg",
            config.objectUrl("abc/my photo.jpg").toString(),
        )
    }

    @Test
    fun `virtual host object url`() {
        assertEquals(
            "https://files.s3.example.com/abc/photo.jpg",
            config.copy(pathStyle = false).objectUrl("abc/photo.jpg").toString(),
        )
    }

    @Test
    fun `keeps port and endpoint path prefix`() {
        val c = config.copy(endpoint = "http://192.168.1.2:3900/s3/")
        assertEquals("http://192.168.1.2:3900/s3/files/a.txt", c.objectUrl("a.txt").toString())
        assertEquals("http://192.168.1.2:3900/s3/files", c.bucketUrl().toString())
    }

    @Test
    fun `validate reports missing and invalid fields`() {
        assertTrue(config.validate().isEmpty())
        assertEquals(
            listOf("Endpoint must be a full URL starting with http:// or https://"),
            config.copy(endpoint = "s3.example.com").validate(),
        )
        assertEquals(5, S3Config("", "", "", "", "").validate().size)
    }

    @Test
    fun `host header omits default ports`() {
        assertEquals("s3.example.com", S3Client.hostHeader(config.objectUrl("a")))
        assertEquals(
            "localhost:3900",
            S3Client.hostHeader(config.copy(endpoint = "http://localhost:3900").objectUrl("a")),
        )
    }
}
