package io.github.tahnok.share2s3

import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tahnok.share2s3.s3.S3Client
import io.github.tahnok.share2s3.upload.AppSettings
import io.github.tahnok.share2s3.upload.KeyTemplate
import io.github.tahnok.share2s3.upload.PublicUrlTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))

        val container = findViewById<View>(R.id.settings_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        textInput(AppSettings.ENDPOINT, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        textInput(AppSettings.REGION, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        textInput(AppSettings.BUCKET, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        textInput(AppSettings.ACCESS_KEY, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        textInput(AppSettings.SECRET_KEY, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        textInput(AppSettings.KEY_TEMPLATE, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        textInput(AppSettings.PUBLIC_URL_TEMPLATE, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        findPreference<EditTextPreference>(AppSettings.SECRET_KEY)?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> {
                if (it.text.isNullOrEmpty()) getString(R.string.secret_not_set) else "••••••••"
            }
        findPreference<EditTextPreference>(AppSettings.KEY_TEMPLATE)?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> {
                val template = it.text.orEmpty().ifBlank { KeyTemplate.DEFAULT }
                getString(R.string.key_template_summary, template, KeyTemplate.render(template, "photo.jpg"))
            }
        findPreference<EditTextPreference>(AppSettings.PUBLIC_URL_TEMPLATE)?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { pref ->
                val settings = AppSettings.load(preferenceManager.sharedPreferences!!)
                val example = runCatching {
                    PublicUrlTemplate.render(pref.text.orEmpty(), settings.s3, "abc123/photo.jpg")
                }.getOrNull()
                val base = if (pref.text.isNullOrBlank()) getString(R.string.public_url_default) else pref.text!!
                if (example != null) getString(R.string.public_url_summary, base, example) else base
            }

        findPreference<Preference>("test_connection")?.setOnPreferenceClickListener {
            testConnection()
            true
        }
        findPreference<Preference>("version")?.summary = requireContext().let {
            it.packageManager.getPackageInfo(it.packageName, 0).versionName
        }
    }

    private fun textInput(key: String, inputType: Int) {
        findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { editText ->
            editText.inputType = inputType
            editText.setSingleLine()
            editText.setSelection(editText.text.length)
        }
    }

    private fun testConnection() {
        val settings = AppSettings.load(PreferenceManager.getDefaultSharedPreferences(requireContext()))
        val problems = settings.s3.validate()
        if (problems.isNotEmpty()) {
            showResult(getString(R.string.test_failed), problems.joinToString("\n"))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { S3Client(settings.s3).headBucket() }
            }
            result.fold(
                onSuccess = {
                    showResult(getString(R.string.test_ok), getString(R.string.test_ok_message, settings.s3.bucket))
                },
                onFailure = { showResult(getString(R.string.test_failed), it.message ?: it.toString()) },
            )
        }
    }

    private fun showResult(title: String, message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

