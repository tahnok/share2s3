package io.github.tahnok.share2s3

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/** Receives files from the Android share sheet and uploads them. */
class ShareActivity : AppCompatActivity() {
    private val viewModel: ShareViewModel by viewModels()

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var settingsButton: Button
    private lateinit var closeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        setFinishOnTouchOutside(false)

        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        settingsButton = findViewById(R.id.settings_button)
        closeButton = findViewById(R.id.close_button)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
        closeButton.setOnClickListener { finish() }

        viewModel.start(this, intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: ShareState) {
        settingsButton.visibility = View.GONE
        closeButton.setText(if (state is ShareState.Uploading) android.R.string.cancel else R.string.close)
        when (state) {
            ShareState.Idle -> Unit
            is ShareState.NotConfigured -> {
                title.setText(R.string.not_configured_title)
                status.text = state.problems.joinToString("\n")
                progress.visibility = View.GONE
                settingsButton.visibility = View.VISIBLE
            }
            is ShareState.Uploading -> {
                val p = state.progress
                if (p == null || p.totalBytes <= 0) {
                    title.setText(R.string.uploading)
                    progress.isIndeterminate = true
                } else {
                    title.text = if (p.count > 1) {
                        getString(R.string.uploading_n_of_m, p.index + 1, p.count)
                    } else {
                        getString(R.string.uploading)
                    }
                    progress.isIndeterminate = false
                    progress.max = 1000
                    progress.setProgressCompat((p.bytesSent * 1000 / p.totalBytes).toInt(), true)
                }
                status.text = p?.let {
                    getString(
                        R.string.upload_progress,
                        it.filename,
                        Formatter.formatShortFileSize(this, it.bytesSent),
                        Formatter.formatShortFileSize(this, it.totalBytes),
                    )
                }.orEmpty()
                progress.visibility = View.VISIBLE
            }
            is ShareState.Done -> {
                val urls = state.results.joinToString("\n") { it.url }
                if (state.copyToClipboard) {
                    getSystemService<ClipboardManager>()
                        ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label), urls))
                    Toast.makeText(this, R.string.uploaded_and_copied, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.uploaded, Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            is ShareState.Failed -> {
                title.setText(R.string.upload_failed)
                status.text = state.message
                progress.visibility = View.GONE
                settingsButton.visibility = View.VISIBLE
            }
        }
    }
}
