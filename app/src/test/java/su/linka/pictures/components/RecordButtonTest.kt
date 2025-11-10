package su.linka.pictures.components

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.Callback
import su.linka.pictures.R
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RecordButtonTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun performClick_withoutFragmentActivity_reportsError() {
        var received: Exception? = null
        val button = RecordButton(context)
        button.setOnRecordListener(object : Callback<File>() {
            override fun onDone(result: File) = Unit

            override fun onFail(error: Exception?) {
                received = error
            }
        })

        button.performClick()

        assertTrue(received is IllegalStateException)
    }

    @Test
    fun onDestroy_resetsRecordingState() {
        val button = RecordButton(context)
        val isRecordingField = RecordButton::class.java.getDeclaredField("isRecording").apply {
            isAccessible = true
            setBoolean(button, true)
        }
        RecordButton::class.java.getDeclaredField("mediaRecorder").apply {
            isAccessible = true
            set(button, null)
        }

        button.onDestroy()

        assertEquals(context.getString(R.string.record_audio), button.text.toString())
        assertEquals(Color.BLACK, button.currentTextColor)
        assertEquals(false, isRecordingField.getBoolean(button))
    }
}
