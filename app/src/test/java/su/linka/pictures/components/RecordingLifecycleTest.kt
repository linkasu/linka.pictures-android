package su.linka.pictures.components

import android.media.MediaRecorder
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaRecorder
import org.robolectric.shadows.ShadowMediaPlayer
import su.linka.pictures.TTS
import su.linka.pictures.Callback
import su.linka.pictures.R
import su.linka.pictures.activity.SetEditActivity
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [RecordingLifecycleTest.RecorderShadow::class])
class RecordingLifecycleTest {
    @Implements(MediaRecorder::class)
    class RecorderShadow : ShadowMediaRecorder() {
        var failStop = false

        @Implementation
        override fun stop() {
            if (failStop) throw RuntimeException("Recording too short")
            super.stop()
        }
    }

    @Test
    fun dismissCancelAndBackReleaseRecorderAndReopenIdle() {
        val controller = Robolectric.buildActivity(SetEditActivity::class.java).setup()
        val activity = controller.get()
        val dialog = field<EditCardDialog>(activity, "cardDialog")
        val (_, set, dir) = ComponentTestUtils.createSet()
        try {
            val dismissals: List<() -> Unit> = listOf(
                { dialog.dismiss() },
                { dialog.cancel() },
                { dialog.findViewById<android.view.View>(R.id.cancel_button)!!.performClick(); Unit },
                {
                    @Suppress("DEPRECATION")
                    dialog.onBackPressed()
                }
            )
            for (dismiss in dismissals) {
                dialog.show(set, null)
                val button = dialog.findViewById<RecordButton>(R.id.record_audio_button)!!
                val (recorder, file) = start(button)
                dismiss()
                assertFalse(dialog.isShowing)
                assertReleased(button, recorder, file)
                dialog.show(set, null)
                assertEquals(activity.getString(R.string.record_audio), button.text.toString())
                assertNull(field<MediaRecorder?>(button, "mediaRecorder"))
                dialog.dismiss()
            }
        } finally {
            controller.pause().stop().destroy()
            ComponentTestUtils.cleanup(dir)
        }
    }

    @Test
    fun activityStopCancelsRecordingAndResumeDoesNotRestartIt() {
        val controller = Robolectric.buildActivity(SetEditActivity::class.java).setup()
        val dialog = field<EditCardDialog>(controller.get(), "cardDialog")
        val (_, set, dir) = ComponentTestUtils.createSet()
        try {
            dialog.show(set, null)
            val button = dialog.findViewById<RecordButton>(R.id.record_audio_button)!!
            val (recorder, file) = start(button)
            controller.pause().stop()
            assertReleased(button, recorder, file)
            controller.start().resume()
            assertNull(field<MediaRecorder?>(button, "mediaRecorder"))
            assertEquals(controller.get().getString(R.string.record_audio), button.text.toString())
        } finally {
            controller.pause().stop().destroy()
            ComponentTestUtils.cleanup(dir)
        }
    }

    @Test
    fun activityDestroyReleasesAllOwnedMediaAndDismissKeepsThemReusable() {
        val controller = Robolectric.buildActivity(SetEditActivity::class.java).setup()
        val dialog = field<EditCardDialog>(controller.get(), "cardDialog")
        val player = field<MediaPlayer>(dialog, "mediaPlayer")
        val tts = field<TextToSpeech>(field<TTS>(dialog, "tts"), "tts")
        val (_, set, dir) = ComponentTestUtils.createSet()
        try {
            dialog.show(set, null)
            dialog.dismiss()
            assertEquals(ShadowMediaPlayer.State.IDLE, shadowOf(player).state)
            assertFalse(shadowOf(tts).isShutdown)
            dialog.show(set, null)
            val button = dialog.findViewById<RecordButton>(R.id.record_audio_button)!!
            controller.pause().stop()
            // Also verify onDestroy itself owns cleanup, even after an earlier onStop.
            val (recorder, file) = start(button)
            controller.destroy()
            assertReleased(button, recorder, file)
            assertFalse(dialog.isShowing)
            assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
            assertTrue(shadowOf(tts).isShutdown)
            dialog.dismiss()
        } finally {
            ComponentTestUtils.cleanup(dir)
        }
    }

    @Test
    fun completedRecordingSurvivesDismissButMissingOrEmptyFilesAreRejected() {
        val controller = Robolectric.buildActivity(SetEditActivity::class.java).setup()
        val dialog = field<EditCardDialog>(controller.get(), "cardDialog")
        val (_, set, dir) = ComponentTestUtils.createSet()
        val files = mutableListOf<File>()
        try {
            dialog.show(set, null)
            val button = dialog.findViewById<RecordButton>(R.id.record_audio_button)!!
            var delivered: File? = null
            var failures = 0
            button.setOnRecordListener(object : Callback<File>() {
                override fun onDone(result: File) { delivered = result }
                override fun onFail(error: Exception?) { failures++ }
            })
            val (_, completed) = start(button)
            files.add(completed)
            button.performClick()
            assertEquals(completed, delivered)
            dialog.dismiss()
            assertTrue(completed.exists())
            for (missing in listOf(false, true)) {
                dialog.show(set, null)
                delivered = null
                val (recorder, file) = start(button)
                files.add(file)
                if (missing) file.delete() else file.writeText("")
                button.performClick()
                assertNull(delivered)
                assertReleased(button, recorder, file)
                dialog.dismiss()
            }
            assertEquals(2, failures)
        } finally {
            controller.pause().stop().destroy()
            files.forEach { it.delete() }
            ComponentTestUtils.cleanup(dir)
        }
    }

    @Test
    fun shortRecordingFailureDoesNotDeliverDeletedFile() {
        val controller = Robolectric.buildActivity(SetEditActivity::class.java).setup()
        val dialog = field<EditCardDialog>(controller.get(), "cardDialog")
        val (_, set, dir) = ComponentTestUtils.createSet()
        try {
            dialog.show(set, null)
            val button = dialog.findViewById<RecordButton>(R.id.record_audio_button)!!
            var delivered: File? = null
            var failed = false
            button.setOnRecordListener(object : Callback<File>() {
                override fun onDone(result: File) { delivered = result }
                override fun onFail(error: Exception?) { failed = true }
            })
            val (recorder, file) = start(button)
            (shadowOf(recorder) as RecorderShadow).failStop = true
            button.performClick()
            assertNull(delivered)
            assertTrue(failed)
            assertReleased(button, recorder, file)
        } finally {
            controller.pause().stop().destroy()
            ComponentTestUtils.cleanup(dir)
        }
    }

    private fun start(button: RecordButton): Pair<MediaRecorder, File> {
        RecordButton::class.java.getDeclaredMethod("startRecording").apply {
            isAccessible = true
            invoke(button)
        }
        val recorder = field<MediaRecorder>(button, "mediaRecorder")
        assertEquals(ShadowMediaRecorder.STATE_RECORDING, shadowOf(recorder).state)
        val file = field<File>(button, "recordFile")
        file.writeText("recorded audio")
        return recorder to file
    }

    private fun assertReleased(button: RecordButton, recorder: MediaRecorder, file: File) {
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, shadowOf(recorder).state)
        assertNull(field<MediaRecorder?>(button, "mediaRecorder"))
        assertFalse(field<Boolean>(button, "isRecording"))
        assertFalse(file.exists())
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target) as T
        }
}
