package su.linka.pictures.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import su.linka.pictures.*
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.components.ParentPasswordDialog
import su.linka.pictures.components.SetImage
import su.linka.pictures.ui.*
import java.io.File
import java.security.MessageDigest

class GridActivity : AppCompatActivity() {
    private var set: Set? = null
    private val work = ActivityWork(this)
    private lateinit var cookie: Cookie
    private lateinit var fileName: String
    internal var gridSettings by mutableStateOf(GridSettings())
        private set
    internal var document by mutableStateOf<BoardDocument?>(null)
        private set
    internal var page by mutableIntStateOf(0)
        private set
    internal var selection by mutableStateOf<List<Int>>(emptyList())
        private set
    internal lateinit var audio: BoardAudioPlayer
        private set
    private var documentIdentity: String? = null
    private lateinit var phraseDraftStore: PhraseDraftStore
    private var phraseDraftToken: String? = null
    private var phraseFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cookie = Cookie(this)
        phraseDraftStore = PhraseDraftStore(this)
        audio = BoardAudioPlayer(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        val name = intent?.getStringExtra(EXTRA_FILE)
        if (name.isNullOrEmpty()) { finish(); return }
        fileName = name
        setContent {
            PicturesTheme {
                val board = document ?: BoardDocument()
                BoardScreen(board, page, selection.mapNotNull { board.cards.getOrNull(it) }, gridSettings.isOutput,
                    gridSettings.isPagesButtons, work.busy, ::onAction,
                    image = { path, _, modifier -> SetImage(path?.let { File(set!!.getFolder(), it) }, modifier) },
                    title = fileName.removeSuffix(".linka"))
            }
        }
        work.run({
            val manager = SetsManager(this)
            val loaded = manager.getSet(name)
            try {
                val archive = manager.getSetFile(name)
                val identity = MessageDigest.getInstance("SHA-256")
                    .digest((name + ":" + archive.length() + ":" + archive.lastModified() + ":" +
                        loaded.getManifest().toJSONObject().toString()).toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val token = savedInstanceState?.getString("phraseDraftToken")
                val restored = if (savedInstanceState?.getString("documentIdentity") == identity && token != null)
                    phraseDraftStore.restore(token, identity) else null
                Triple(loaded, identity, restored)
            } catch (error: Exception) { loaded.close(); throw error }
        }, { (loaded, identity, restored) ->
            set = loaded
            document = loaded.getManifest().toDocument()
            documentIdentity = identity
            gridSettings = GridSettings.fromInt(cookie.getSetSettings(name, DEFAULT_GRID_SETTINGS))
            if (savedInstanceState?.getString("documentIdentity") == identity) {
                page = savedInstanceState.getInt("page").coerceIn(0, pageCount() - 1)
                savedInstanceState.getString("phraseDraftToken")?.let { token ->
                    if (restored != null) {
                        phraseDraftToken = token
                        selection = restored.filter { document!!.cards.getOrNull(it)?.cardType in 0..1 }
                    } else Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
                }
            }
        }, {
            Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
            finish()
        }, abandoned = { it.first.close() })
    }

    internal fun onAction(action: BoardAction) {
        if (isDestroyed || work.busy) return
        val board = document ?: return
        when (action) {
            is BoardAction.SelectCard -> {
                val card = board.cards.getOrNull(action.absoluteIndex) ?: return
                if (card.cardType !in 0..1) return
                Telemetry.logEvent(AnalyticsEvents.CARD_SELECT, null)
                if (gridSettings.isOutput) selection = selection + action.absoluteIndex
                else if (card.cardType == 0) audio.play(set!!, listOf(card))
            }
            is BoardAction.Page -> page = action.index.coerceIn(0, pageCount() - 1)
            BoardAction.Backspace -> { audio.stop(); selection = selection.dropLast(1) }
            BoardAction.Clear -> { audio.stop(); selection = emptyList() }
            BoardAction.Speak -> {
                if (audio.playing) audio.stop()
                else if (gridSettings.isOutput) {
                    val phrase = selection.map { board.cards[it] }
                    if (board.withoutSpace) {
                        if (!audio.speak(phraseText(phrase))) Toast.makeText(this,
                            if (audio.speechState == BoardAudioPlayer.SpeechState.INITIALIZING) R.string.speech_not_ready
                            else R.string.speech_unavailable, Toast.LENGTH_LONG).show()
                    } else audio.play(set!!, phrase)
                }
            }
            BoardAction.Settings -> ParentPasswordDialog.run(this) {
                Telemetry.logEvent(AnalyticsEvents.OPEN_GRID_SETTINGS, null)
                showSettings()
            }
            BoardAction.Close -> close()
        }
    }

    private fun pageCount(): Int = document?.let { board ->
        maxOf(1, (board.cards.size + board.rows * board.columns - 1) / (board.rows * board.columns))
    } ?: 1

    private fun showSettings() {
        audio.stop()
        val view = GridSettingsView(this)
        view.setSettings(gridSettings.copy())
        AlertDialog.Builder(this).setTitle(R.string.grid_activity_settings).setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                view.commit()
                gridSettings = view.getSettings()
                cookie.setSetSettings(fileName, gridSettings.toInt())
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun capturePhrase() {
        val identity = documentIdentity ?: return
        if (phraseFinished) return
        try {
            phraseDraftToken = phraseDraftStore.capture(phraseDraftToken, identity, selection.toIntArray())
        } catch (_: Exception) {
            Toast.makeText(this, R.string.set_save_error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capturePhrase()
        outState.putString("documentIdentity", documentIdentity)
        outState.putInt("page", page)
        phraseDraftToken?.takeUnless { phraseFinished }?.let { outState.putString("phraseDraftToken", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (::audio.isInitialized) audio.stop()
        capturePhrase()
        super.onStop()
    }

    override fun onDestroy() {
        if (::audio.isInitialized) audio.close()
        set?.close()
        super.onDestroy()
    }

    private fun close() = ParentPasswordDialog.run(this) {
        audio.stop()
        phraseFinished = true
        phraseDraftToken?.let { phraseDraftStore.discard(it) }
        finish()
    }

    companion object {
        const val EXTRA_FILE = "file"
        private const val DEFAULT_GRID_SETTINGS = 3
    }
}
