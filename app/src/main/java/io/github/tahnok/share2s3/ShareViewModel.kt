package io.github.tahnok.share2s3

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import io.github.tahnok.share2s3.upload.AppSettings
import io.github.tahnok.share2s3.upload.UploadProgress
import io.github.tahnok.share2s3.upload.UploadResult
import io.github.tahnok.share2s3.upload.UploadSource
import io.github.tahnok.share2s3.upload.Uploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

sealed interface ShareState {
    data object Idle : ShareState
    data class NotConfigured(val problems: List<String>) : ShareState
    data class Uploading(val progress: UploadProgress?) : ShareState
    data class Done(val results: List<UploadResult>, val copyToClipboard: Boolean) : ShareState
    data class Failed(val message: String) : ShareState
}

class ShareViewModel : ViewModel() {
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state

    /** Starts uploading what [intent] shares. Only the first call per ViewModel has any effect. */
    fun start(context: Context, intent: Intent) {
        if (_state.value != ShareState.Idle) return

        val app = context.applicationContext
        val settings = AppSettings.load(PreferenceManager.getDefaultSharedPreferences(app))
        val problems = settings.s3.validate()
        if (problems.isNotEmpty()) {
            _state.value = ShareState.NotConfigured(problems)
            return
        }

        val sources = sourcesFrom(app.contentResolver, intent)
        if (sources.isEmpty()) {
            _state.value = ShareState.Failed(app.getString(R.string.nothing_to_upload))
            return
        }

        _state.value = ShareState.Uploading(null)
        viewModelScope.launch {
            _state.value = try {
                val uploader = Uploader(settings, app.cacheDir)
                val results = runInterruptible(Dispatchers.IO) {
                    uploader.uploadAll(sources) { _state.value = ShareState.Uploading(it) }
                }
                ShareState.Done(results, settings.copyToClipboard)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ShareState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun sourcesFrom(resolver: ContentResolver, intent: Intent): List<UploadSource> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) return uris.map { sourceFor(resolver, it, intent.type) }

        // Plain text shared without a file, e.g. a note or a selection: upload it as a .txt file.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (intent.action == Intent.ACTION_SEND && !text.isNullOrEmpty()) {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
            val bytes = text.toByteArray()
            return listOf(UploadSource("${subject ?: "shared-text"}.txt", "text/plain; charset=utf-8") { bytes.inputStream() })
        }
        return emptyList()
    }

    private fun sourceFor(resolver: ContentResolver, uri: Uri, intentType: String?): UploadSource {
        val mime = resolver.getType(uri)
            ?: intentType?.takeUnless { it.contains('*') }
            ?: "application/octet-stream"

        var name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "upload"

        if (!name.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
        }
        return UploadSource(name, mime) {
            resolver.openInputStream(uri) ?: throw java.io.IOException("Could not open $uri")
        }
    }
}
