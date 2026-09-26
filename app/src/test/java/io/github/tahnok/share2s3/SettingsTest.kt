package io.github.tahnok.share2s3

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import io.github.tahnok.share2s3.upload.AppSettings
import io.github.tahnok.share2s3.upload.KeyTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    @Test
    fun `defaults suit Garage`() {
        val settings = AppSettings.load(prefs)
        assertEquals("garage", settings.s3.region)
        assertTrue(settings.s3.pathStyle)
        assertTrue(settings.copyToClipboard)
        assertEquals(KeyTemplate.DEFAULT, settings.keyTemplate)
        assertEquals("", settings.publicUrlTemplate)
    }

    @Test
    fun `values are trimmed`() {
        prefs.edit {
            putString(AppSettings.ENDPOINT, " https://s3.example.com \n")
            putString(AppSettings.ACCESS_KEY, " GK1 ")
            putBoolean(AppSettings.PATH_STYLE, false)
            putBoolean(AppSettings.COPY_TO_CLIPBOARD, false)
        }
        val settings = AppSettings.load(prefs)
        assertEquals("https://s3.example.com", settings.s3.endpoint)
        assertEquals("GK1", settings.s3.accessKey)
        assertFalse(settings.s3.pathStyle)
        assertFalse(settings.copyToClipboard)
    }

    @Test
    fun `settings screen opens`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        assertNotNull(fragment.findPreference(AppSettings.ENDPOINT))
        assertNotNull(fragment.findPreference(AppSettings.PUBLIC_URL_TEMPLATE))
    }
}
