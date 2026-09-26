package io.github.tahnok.share2s3

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import io.github.tahnok.share2s3.upload.AppSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ShareActivityTest {
    private val server = MockWebServer()
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        server.start()
        prefs.edit(commit = true) {
            putString(AppSettings.ENDPOINT, server.url("/").toString())
            putString(AppSettings.BUCKET, "bucket")
            putString(AppSettings.ACCESS_KEY, "GKkey")
            putString(AppSettings.SECRET_KEY, "secret")
            putString(AppSettings.KEY_TEMPLATE, "shared/{filename}")
            putString(AppSettings.PUBLIC_URL_TEMPLATE, "https://files.example.com/{key}")
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun shareFile(name: String, content: String): Uri {
        val file = File(context.cacheDir, name).apply { writeText(content) }
        return Uri.fromFile(file)
    }

    private fun launch(intent: Intent): ShareActivity {
        val activity = Robolectric.buildActivity(ShareActivity::class.java, intent).setup().get()
        // Uploads run on a background dispatcher; pump the main looper until the UI settles.
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.isFinishing) return activity
            Thread.sleep(25)
        }
        return activity
    }

    private fun ShareActivity.status() = findViewById<TextView>(R.id.status).text.toString()

    private fun clipboardText(): String? =
        context.getSystemService<ClipboardManager>()!!.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun `single file is uploaded and link copied`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, shareFile("cat.png", "meow"))

        val activity = launch(intent)

        assertTrue(activity.status(), activity.isFinishing)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals("/bucket/shared/cat.png", request.path)
        assertEquals("meow", request.body.readUtf8())
        assertEquals("https://files.example.com/shared/cat.png", clipboardText())
    }

    @Test
    fun `multiple files copy one link per line`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(shareFile("a.txt", "a"), shareFile("b.txt", "b")),
            )

        launch(intent)

        assertEquals(2, server.requestCount)
        assertEquals(
            "https://files.example.com/shared/a.txt\nhttps://files.example.com/shared/b.txt",
            clipboardText(),
        )
    }

    @Test
    fun `shared text is uploaded as a text file`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "hello there")
            .putExtra(Intent.EXTRA_SUBJECT, "note")

        launch(intent)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/bucket/shared/note.txt", request.path)
        assertEquals("hello there", request.body.readUtf8())
    }

    @Test
    fun `clipboard is left alone when disabled`() {
        prefs.edit(commit = true) { putBoolean(AppSettings.COPY_TO_CLIPBOARD, false) }
        server.enqueue(MockResponse().setResponseCode(200))
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, shareFile("x.bin", "x"))

        val activity = launch(intent)

        assertTrue(activity.status(), activity.isFinishing)
        assertEquals(1, server.requestCount)
        assertEquals(null, clipboardText())
    }

    @Test
    fun `upload errors are shown and the dialog stays open`() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("<Error><Code>AccessDenied</Code><Message>nope</Message></Error>"),
        )
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, shareFile("x.bin", "x"))

        val activity = launch(intent)

        assertFalse(activity.isFinishing)
        assertEquals(context.getString(R.string.upload_failed), activity.findViewById<TextView>(R.id.title).text.toString())
        assertEquals("HTTP 403 AccessDenied: nope", activity.status())
        assertEquals(null, clipboardText())
    }

    @Test
    fun `missing configuration points to settings`() {
        prefs.edit(commit = true) { clear() }
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, shareFile("x.bin", "x"))

        val activity = launch(intent)

        assertFalse(activity.isFinishing)
        assertEquals(0, server.requestCount)
        assertEquals(
            context.getString(R.string.not_configured_title),
            activity.findViewById<TextView>(R.id.title).text.toString(),
        )
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settings_button).visibility)
    }
}
