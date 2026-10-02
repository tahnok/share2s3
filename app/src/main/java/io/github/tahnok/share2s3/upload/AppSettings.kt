package io.github.tahnok.share2s3.upload

import android.content.SharedPreferences
import io.github.tahnok.share2s3.s3.S3Config

/** Everything configured on the settings screen. */
data class AppSettings(
    val s3: S3Config,
    val keyTemplate: String = KeyTemplate.DEFAULT,
    val publicUrlTemplate: String = "",
    val copyToClipboard: Boolean = true,
) {
    companion object {
        // Keys shared with res/xml/preferences.xml.
        const val ENDPOINT = "endpoint"
        const val REGION = "region"
        const val BUCKET = "bucket"
        const val ACCESS_KEY = "access_key"
        const val SECRET_KEY = "secret_key"
        const val PATH_STYLE = "path_style"
        const val KEY_TEMPLATE = "key_template"
        const val PUBLIC_URL_TEMPLATE = "public_url_template"
        const val COPY_TO_CLIPBOARD = "copy_to_clipboard"

        const val DEFAULT_REGION = "garage"

        fun load(prefs: SharedPreferences) = AppSettings(
            s3 = S3Config(
                endpoint = prefs.getString(ENDPOINT, null).orEmpty().trim(),
                region = prefs.getString(REGION, null).orEmpty().trim().ifEmpty { DEFAULT_REGION },
                bucket = prefs.getString(BUCKET, null).orEmpty().trim(),
                accessKey = prefs.getString(ACCESS_KEY, null).orEmpty().trim(),
                secretKey = prefs.getString(SECRET_KEY, null).orEmpty().trim(),
                pathStyle = prefs.getBoolean(PATH_STYLE, true),
            ),
            keyTemplate = prefs.getString(KEY_TEMPLATE, null).orEmpty().ifBlank { KeyTemplate.DEFAULT },
            publicUrlTemplate = prefs.getString(PUBLIC_URL_TEMPLATE, null).orEmpty().trim(),
            copyToClipboard = prefs.getBoolean(COPY_TO_CLIPBOARD, true),
        )
    }
}
