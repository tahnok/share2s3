package io.github.tahnok.share2s3.upload

import io.github.tahnok.share2s3.s3.S3Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class KeyTemplateTest {
    private val now = Instant.parse("2024-03-09T12:34:56Z")

    private fun render(template: String, filename: String = "My Photo.JPG") =
        KeyTemplate.render(template, filename, now, Random(42))

    @Test
    fun `default template is random folder plus filename`() {
        val key = render(KeyTemplate.DEFAULT)
        assertTrue(key, Regex("[a-z0-9]{8}/My_Photo\\.JPG").matches(key))
    }

    @Test
    fun `blank template falls back to default`() {
        assertEquals(render(KeyTemplate.DEFAULT), render("  "))
    }

    @Test
    fun `date and name placeholders`() {
        assertEquals(
            "2024/03/09/My_Photo-${now.epochSecond}.JPG",
            render("{year}/{month}/{day}/{name}-{timestamp}.{ext}"),
        )
        assertEquals("uploads/2024-03-09/My_Photo.JPG", render("uploads/{date}/{filename}"))
    }

    @Test
    fun `uuid placeholder`() {
        val key = render("{uuid}.{ext}")
        assertTrue(key, Regex("[0-9a-f-]{36}\\.JPG").matches(key))
    }

    @Test
    fun `unknown placeholders are left alone`() {
        assertEquals("{nope}/My_Photo.JPG", render("{nope}/{filename}"))
    }

    @Test
    fun `leading and duplicate slashes are removed`() {
        assertEquals("a/b/My_Photo.JPG", render("/a//b/{filename}"))
    }

    @Test
    fun `filenames are sanitised`() {
        assertEquals("passwd", KeyTemplate.sanitizeFilename("../../etc/passwd"))
        assertEquals("a_b.txt", KeyTemplate.sanitizeFilename("a \t b.txt"))
        assertEquals("hidden", KeyTemplate.sanitizeFilename(".hidden"))
        assertEquals("upload", KeyTemplate.sanitizeFilename(""))
        assertEquals("été.png", KeyTemplate.sanitizeFilename("été.png"))
    }

    @Test
    fun `file without extension`() {
        assertEquals("README-", render("{name}-{ext}", "README"))
    }
}

class PublicUrlTemplateTest {
    private val config = S3Config(
        endpoint = "https://s3.example.com",
        region = "garage",
        bucket = "files",
        accessKey = "a",
        secretKey = "b",
    )

    @Test
    fun `empty template uses the S3 url`() {
        assertEquals(
            "https://s3.example.com/files/abc/my%20photo.jpg",
            PublicUrlTemplate.render("", config, "abc/my photo.jpg"),
        )
    }

    @Test
    fun `custom template substitutes encoded key`() {
        assertEquals(
            "https://cdn.example.com/abc/my%20photo.jpg",
            PublicUrlTemplate.render("https://cdn.example.com/{key}", config, "abc/my photo.jpg"),
        )
    }

    @Test
    fun `bucket and filename placeholders`() {
        assertEquals(
            "https://files.web.garage.example/x/%C3%A9t%C3%A9.png",
            PublicUrlTemplate.render("https://{bucket}.web.garage.example/x/{filename}", config, "abc/été.png"),
        )
    }
}
