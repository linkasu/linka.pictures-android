package su.linka.pictures.activity

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Switch
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowToast
import java.io.File
import su.linka.pictures.*
import su.linka.pictures.R
import su.linka.pictures.ui.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [ControlledTextToSpeech::class])
class ComposeScreensIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = SetsManager(context)
    private lateinit var clock: ComposeHostClock

    @Before fun prepare() {
        // Popup position tracking posts a frame forever; advance scheduled vsyncs explicitly.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        clock = ComposeHostClock()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        TelemetryPrivacyPreferences(context).setAnalyticsEnabled(false)
        Cookie(context).set(Cookie.ASSETS_LOADER, true)
        val source = manager.createSet()
        try {
            File(source.getFolder(), "hello.audio").writeText("test clip")
            val image = source.saveBitmap(android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888))
            source.getManifest().apply {
                rows = 1; columns = 3
                cards.addAll(listOf(Card(0, imagePath = image.name, title = "Hello", audioPath = "hello.audio"), Card(1, cardType = 1), Card(2, cardType = 2), Card(3, title = "World")))
            }
            manager.save(source, "screen-path.linka", object : Callback<Any?>() {
                override fun onDone(result: Any?) = Unit
                override fun onFail(error: Exception?) = throw AssertionError(error)
            })
        } finally { source.close() }
    }

    @After fun releaseClock() { clock.close() }

    @Test fun actualBoardSemanticsNameCardsOnceAndDoNotExposeEmptyCellsAsActions() {
        val intent = Intent(context, GridActivity::class.java).putExtra(GridActivity.EXTRA_FILE, "screen-path.linka")
        val owner = Robolectric.buildActivity(GridActivity::class.java, intent).setup()
        try {
            val nodes = composeNodes(owner.get())
            val cardActions = nodes.filter { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text in listOf("Hello", "Space") }
            }
            assertEquals(2, cardActions.size)
            assertTrue(cardActions.none { it.config.contains(SemanticsProperties.Disabled) })
            assertFalse(nodes.any { it.config.contains(SemanticsProperties.Disabled) &&
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().isEmpty() })
            assertEquals(1, nodes.count { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Hello" }
            })
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun libraryRoutesToRealBoardEditorAndSettingsScreensThroughComposeActions() {
        val library = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertComposeHost(library.get())
            clickComposeText(library.get(), "screen-path")
            val boardIntent = shadowOf(library.get()).nextStartedActivity
            assertEquals(GridActivity::class.java.name, boardIntent.component!!.className)
            library.pause().stop()
            val board = Robolectric.buildActivity(GridActivity::class.java, boardIntent).setup()
            try {
                assertComposeHost(board.get())
                assertTrue(composeTexts(board.get()).contains("screen-path"))
                clickComposeText(board.get(), "Hello")
                assertEquals(listOf(0), board.get().selection)
            } finally { board.pause().stop().destroy() }
            library.restart().start().resume().visible()
            composeNodes(library.get())
            library.windowFocusChanged(true)

            clickComposeText(library.get(), "Options for screen-path")
            clickComposeText(library.get(), "Edit")
            val editorIntent = shadowOf(library.get()).nextStartedActivityForResult.intent
            assertEquals(SetEditActivity::class.java.name, editorIntent.component!!.className)
            library.pause().stop()
            val editor = Robolectric.buildActivity(SetEditActivity::class.java, editorIntent).setup()
            try {
                assertComposeHost(editor.get())
                assertTrue(composeTexts(editor.get()).contains("screen-path"))
                clickComposeText(editor.get(), "Hello")
                assertTrue(ShadowDialog.getLatestDialog() is su.linka.pictures.components.EditCardDialog)
                assertTrue(ShadowDialog.getLatestDialog().isShowing)
            } finally { editor.pause().stop().destroy() }
            library.restart().start().resume().visible()
            composeNodes(library.get())
            library.windowFocusChanged(true)

            clickComposeText(library.get(), "Settings")
            val settingsIntent = shadowOf(library.get()).nextStartedActivity
            assertEquals(SettingsActivity::class.java.name, settingsIntent.component!!.className)
            val settings = Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).setup()
            try {
                assertComposeHost(settings.get())
                assertTrue(composeTexts(settings.get()).contains("Parental control"))
                clickComposeText(settings.get(), "Send usage analytics")
                assertTrue(settings.get().analyticsEnabled)
                assertEquals(TelemetryCollectionState.ENABLED, TelemetryPrivacyPreferences(context).state())
            } finally { settings.pause().stop().destroy() }
        } finally { library.pause().stop().destroy() }
    }

    @Test fun invisibleSpaceRemainsInHistoryAndDirectModePreservesPhraseAndPaging() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10000, 0) }
        val intent = Intent(context, GridActivity::class.java).putExtra(GridActivity.EXTRA_FILE, "screen-path.linka")
        var owner = Robolectric.buildActivity(GridActivity::class.java, intent).setup()
        try {
            val activity = owner.get()
            awaitHostWork(activity)
            activity.onAction(BoardAction.SelectCard(0))
            activity.onAction(BoardAction.SelectCard(1))
            activity.onAction(BoardAction.SelectCard(2))
            assertEquals(listOf(0, 1), activity.selection)
            awaitComposeTextsForTag(activity, "phrase-cards", listOf("Hello"))
            activity.onAction(BoardAction.Backspace)
            assertEquals(listOf(0), activity.selection)
            activity.onAction(BoardAction.SelectCard(1))
            activity.onAction(BoardAction.Page(1))
            setOptions(activity, output = false, paging = false)
            activity.onAction(BoardAction.SelectCard(0))
            assertEquals(listOf(0, 1), activity.selection)
            assertTrue("Direct mode starts the clip immediately", activity.audio.playing)
            assertFalse(activity.gridSettings.isPagesButtons)
            assertEquals(1, activity.page)
            assertEquals(4, activity.document!!.cards.size)
            setOptions(activity, output = true, paging = true)
            assertEquals(listOf(0, 1), activity.selection)
            assertEquals(1, activity.page)
            val state = Bundle()
            owner.saveInstanceState(state).pause().stop().destroy()
            owner = Robolectric.buildActivity(GridActivity::class.java, intent).setup(state)
            assertComposeHost(owner.get())
            assertEquals(listOf(0, 1), owner.get().selection)
            assertEquals(1, owner.get().page)
            assertFalse(owner.get().audio.playing)
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun keyboardModeSpeaksWholePhraseOnlyAfterReadyAndExtraSpeakStops() {
        enableKeyboardMode()
        val intent = Intent(context, GridActivity::class.java).putExtra(GridActivity.EXTRA_FILE, "screen-path.linka")
        var owner = Robolectric.buildActivity(GridActivity::class.java, intent).setup()
        try {
            val activity = owner.get()
            awaitHostWork(activity)
            val engine = ControlledTextToSpeech.latest
            activity.onAction(BoardAction.SelectCard(0))
            activity.onAction(BoardAction.SelectCard(1))
            activity.onAction(BoardAction.SelectCard(0))
            assertTrue(composeTexts(activity).contains("Hello Hello"))
            activity.onAction(BoardAction.Speak)
            assertFalse(engine.spoken)
            assertEquals(activity.getString(R.string.speech_not_ready), ShadowToast.getTextOfLatestToast())
            engine.initialization.onInit(TextToSpeech.SUCCESS)
            composeNodes(activity)
            assertTrue(activity.audio.ttsReady)
            assertFalse(engine.spoken) // Readiness never auto-starts a discarded request.
            activity.onAction(BoardAction.Speak)
            assertEquals("Hello Hello", engine.spokenText)
            assertTrue(activity.audio.playing)
            activity.onAction(BoardAction.Speak)
            assertTrue(engine.stopped)
            assertFalse(activity.audio.playing)
            val state = Bundle()
            owner.saveInstanceState(state).pause().stop().destroy()
            assertTrue(engine.closed)
            owner = Robolectric.buildActivity(GridActivity::class.java, intent).setup(state)
            assertComposeHost(owner.get())
            assertEquals(listOf(0, 1, 0), owner.get().selection)
            assertFalse(ControlledTextToSpeech.latest.spoken)
            assertFalse(owner.get().audio.playing)
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun failedSpeechEngineReportsErrorWithoutBlockingCardSelection() {
        enableKeyboardMode()
        val intent = Intent(context, GridActivity::class.java).putExtra(GridActivity.EXTRA_FILE, "screen-path.linka")
        val owner = Robolectric.buildActivity(GridActivity::class.java, intent).setup()
        try {
            val activity = owner.get()
            ControlledTextToSpeech.latest.initialization.onInit(TextToSpeech.ERROR)
            clickComposeText(activity, "Hello")
            assertEquals(BoardAudioPlayer.SpeechState.ERROR, activity.audio.speechState)
            activity.onAction(BoardAction.Speak)
            assertEquals(activity.getString(R.string.speech_unavailable), ShadowToast.getTextOfLatestToast())
            activity.onAction(BoardAction.SelectCard(0))
            assertEquals(listOf(0, 0), activity.selection)
            assertFalse(activity.audio.playing)
            assertFalse(ControlledTextToSpeech.latest.spoken)
        } finally { owner.pause().stop().destroy() }
    }

    private fun enableKeyboardMode() {
        val source = manager.getSet("screen-path.linka")
        try {
            source.getManifest().withoutSpace = true
            manager.save(source, "screen-path.linka", object : Callback<Any?>() {
                override fun onDone(result: Any?) = Unit
                override fun onFail(error: Exception?) = throw AssertionError(error)
            })
        } finally { source.close() }
    }

    private fun setOptions(activity: GridActivity, output: Boolean, paging: Boolean) {
        activity.onAction(BoardAction.Settings)
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.findViewById<Switch>(R.id.is_output_switch)!!.isChecked = output
        dialog.findViewById<Switch>(R.id.is_page_buttons_switch)!!.isChecked = paging
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        composeNodes(activity)
    }
}
