package io.github.tahnok.share2s3.upload

import io.github.tahnok.share2s3.s3.S3Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/**
 * Builds object keys from a template such as `{random}/{filename}`.
 *
 * Supported placeholders:
 * - `{filename}` sanitised original file name, e.g. `holiday photo.jpg` -> `holiday_photo.jpg`
 * - `{name}` file name without extension, `{ext}` extension without the dot
 * - `{uuid}` random UUID, `{random}` 8 random lowercase alphanumeric characters
 * - `{date}` `yyyy-MM-dd`, `{year}`, `{month}`, `{day}`, `{timestamp}` (Unix seconds), all UTC
 */
object KeyTemplate {
    const val DEFAULT = "{random}/{filename}"

    private val PLACEHOLDER = Regex("\\{([a-z]+)\\}")
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun render(
        template: String,
        filename: String,
        now: Instant = Instant.now(),
        random: Random = Random.Default,
    ): String {
        val clean = sanitizeFilename(filename)
        val dot = clean.lastIndexOf('.')
        val name = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot + 1) else ""
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()

        val effective = template.trim().ifEmpty { DEFAULT }
        val rendered = PLACEHOLDER.replace(effective) { match ->
            when (match.groupValues[1]) {
                "filename" -> clean
                "name" -> name
                "ext" -> ext
                "uuid" -> UUID(random.nextLong(), random.nextLong()).toString()
                "random" -> (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
                "date" -> date.toString()
                "year" -> "%04d".format(date.year)
                "month" -> "%02d".format(date.monthValue)
                "day" -> "%02d".format(date.dayOfMonth)
                "timestamp" -> now.epochSecond.toString()
                else -> match.value
            }
        }
        // S3 keys must not start with a slash, and empty path segments make ugly URLs.
        return rendered.split('/').filter { it.isNotEmpty() }.joinToString("/").ifEmpty { clean }
    }

    /** Makes a file name safe to use as a single key segment. */
    fun sanitizeFilename(filename: String): String {
        val cleaned = filename
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filter { !it.isISOControl() }
            .trim()
            .replace(Regex("\\s+"), "_")
            .trimStart('.')
        return cleaned.ifEmpty { "upload" }
    }
}

/**
 * Builds the URL copied to the clipboard.
 *
 * An empty template produces the S3 object URL. Otherwise these placeholders are supported:
 * - `{key}` the URL-encoded object key (slashes kept), e.g. `https://files.example.com/{key}`
 * - `{bucket}` the bucket name
 * - `{filename}` the URL-encoded last segment of the key
 */
object PublicUrlTemplate {
    fun render(template: String, config: S3Config, key: String): String {
        if (template.isBlank()) return config.objectUrl(key).toString()
        val encodedKey = S3Config.encodeKey(key)
        return template.trim()
            .replace("{key}", encodedKey)
            .replace("{bucket}", config.bucket)
            .replace("{filename}", encodedKey.substringAfterLast('/'))
    }
}
