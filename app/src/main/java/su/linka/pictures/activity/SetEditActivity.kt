package su.linka.pictures.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import org.json.JSONObject
import su.linka.pictures.*
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.components.ConfirmDialog
import su.linka.pictures.components.EditCardDialog
import su.linka.pictures.components.InputDialog
import su.linka.pictures.components.SetImage
import su.linka.pictures.shared.BoardLimits
import su.linka.pictures.shared.GridDimensions
import su.linka.pictures.ui.*
import java.io.File

class SetEditActivity : AppCompatActivity() {
    private lateinit var setsManager: SetsManager
    private lateinit var cardDialog: EditCardDialog
    private var set: Set? = null
    private val work = ActivityWork(this)
    private var setName: String? = null
    internal var document by mutableStateOf<BoardDocument?>(null)
        private set
    internal var page by mutableIntStateOf(0)
        private set
    private lateinit var draftStore: EditorDraftStore
    private var draftToken: String? = null
    private var editedPosition = 0
    private var editedNextPage = false
    private var draftFinished = false
    private var pendingMediaResult: Triple<Int, Int, Intent?>? = null
    private val exportController = SetExportController(this,
        onSuccess = { Toast.makeText(this, R.string.set_export_success, Toast.LENGTH_LONG).show() },
        onError = { Toast.makeText(this, R.string.set_export_error, Toast.LENGTH_LONG).show() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportController.restoreState(savedInstanceState)
        cardDialog = EditCardDialog(this)
        setsManager = SetsManager(this)
        draftStore = EditorDraftStore(this)
        cardDialog.onChooseImage = { captureDraft() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        setContent {
            PicturesTheme {
                EditorScreen(document ?: BoardDocument(), page, document == null || work.busy || exportController.isPending, null, ::onAction,
                    image = { path, _, modifier -> SetImage(path?.let { File(set!!.getFolder(), it) }, modifier) },
                    title = setName?.removeSuffix(".linka"))
            }
        }
        setName = intent?.getStringExtra(EXTRA_FILE)
        work.run({ draftStore.restore(savedInstanceState?.getString(DRAFT_TOKEN)) }, { restored ->
        if (restored != null) {
            try {
                restoreDraft(restored)
                deliverPendingMediaResult()
            } catch (_: Exception) {
                draftFinished = true // Preserve the unreadable draft, never overwrite it with partial state.
                Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
                finish()
            }
            return@run
        }
        if (savedInstanceState?.containsKey(DRAFT_TOKEN) == true) {
            draftFinished = true
            Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
            finish()
            return@run
        }
        if (setName == null) showEnterTitleDialog() else loadFromFile()
        }, { openFailed() }, abandoned = { it?.set?.close() })
    }

    internal fun onAction(action: EditorAction) {
        if (isDestroyed || work.busy || exportController.isPending) return
        val current = set ?: return
        when (action) {
            is EditorAction.EditCard -> onCardSelected(current.getManifest().cards.getOrNull(action.absoluteIndex), action.absoluteIndex, false)
            is EditorAction.Page -> page = action.index.coerceIn(0, pageCount() - 1)
            EditorAction.NewPage -> ConfirmDialog.showConfirmDialog(this, R.string.confirm_page_creation, object : Callback<Any?>() {
                override fun onDone(result: Any?) = onCardSelected(null, pageCount() * pageSize(), true)
                override fun onFail(error: Exception?) = Unit
            })
            is EditorAction.Resize -> {
                if (runCatching { GridDimensions(action.rows, action.columns) }.isFailure) {
                    Toast.makeText(this, R.string.invalid_grid_dimensions, Toast.LENGTH_LONG).show()
                    return
                }
                current.getManifest().rows = action.rows
                current.getManifest().columns = action.columns
                page = 0
                refreshDocument()
                Telemetry.logEvent(AnalyticsEvents.RESIZE_GRID, null)
            }
            is EditorAction.WithoutSpace -> {
                current.getManifest().withoutSpace = action.enabled
                refreshDocument()
                Telemetry.logEvent(AnalyticsEvents.SET_WITHOUT_SPACE, null)
            }
            EditorAction.Save -> save()
            EditorAction.Back -> close()
            EditorAction.Export -> exportController.export(setName ?: "set.linka") { checkNotNull(set) }
        }
    }

    private fun pageSize(): Int = set!!.getManifest().let { it.rows * it.columns }
    private fun pageCount(): Int = maxOf(1, (set!!.getManifest().cards.size + pageSize() - 1) / pageSize())
    private fun refreshDocument() { document = set?.getManifest()?.toDocument() }

    private fun restoreDraft(draft: EditorDraftStore.Draft) {
        val state = draft.state
        val name = state.getString("name")
        require(name.isNotEmpty() && File(name).name == name)
        draftToken = draft.token
        set = draft.set
        setName = name
        refreshDocument()
        page = state.optInt("page").coerceIn(0, pageCount() - 1)
        state.optJSONObject("dialog")?.let { dialogState ->
            editedPosition = state.getInt("position")
            require(editedPosition in 0 until BoardLimits.MAX_CARDS)
            require(editedPosition.toLong() in 0..pageCount().toLong() * pageSize())
            editedNextPage = state.optBoolean("nextPage")
            cardDialog.restoreState(draft.set, dialogState, draftStore.mediaDirectory(draft.token))
            bindCardCallback()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (document == null) {
            pendingMediaResult = Triple(requestCode, resultCode, data)
            return
        }
        cardDialog.onActivityResult(requestCode, resultCode, data)
    }

    private fun deliverPendingMediaResult() {
        val (request, result, data) = pendingMediaResult ?: return
        pendingMediaResult = null
        cardDialog.onActivityResult(request, result, data)
    }

    private fun onCardSelected(card: Card?, pos: Int, nextPage: Boolean) {
        val current = set ?: return
        if (pos !in 0 until BoardLimits.MAX_CARDS) {
            Toast.makeText(this, R.string.card_limit_reached, Toast.LENGTH_LONG).show()
            return
        }
        editedPosition = pos
        editedNextPage = nextPage
        cardDialog.show(current, if (card != null && card.cardType < 3) card else null)
        bindCardCallback()
    }

    private fun bindCardCallback() {
        val pos = editedPosition
        val nextPage = editedNextPage
        cardDialog.setCallback(object : Callback<Card>() {
            override fun onDone(result: Card) {
                addCard(pos, result)
                if (nextPage) page = pos / pageSize()
            }
            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun addCard(pos: Int, card: Card) {
        val current = set ?: return
        Telemetry.logEvent(AnalyticsEvents.ADD_CARD, null)
        current.addCard(pos, card)
        refreshDocument()
    }

    private fun showEnterTitleDialog() {
        InputDialog.showDialog(this, R.string.set_name, object : Callback<String>() {
            override fun onDone(result: String) {
                setName = "$result.linka"
                openDraft { setsManager.createSet() }
            }
            override fun onFail(error: Exception?) { finish() }
        })
    }

    private fun loadFromFile() {
        val name = setName ?: return
        openDraft { setsManager.getSet(name) }
    }

    private fun openDraft(source: () -> Set) {
        val state = snapshotState(null)
        work.run({
            val loaded = source()
            try {
                draftStore.create(loaded).also { draftStore.capture(it.token, it.set, state) }
            } finally { loaded.close() }
        }, { draft ->
            draftToken = draft.token
            set = draft.set
            refreshDocument()
            deliverPendingMediaResult()
        }, { openFailed() }, abandoned = { it.set.close() })
    }

    private fun openFailed() {
        draftFinished = true
        Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun save() {
        if (cardDialog.isShowing || work.busy || exportController.isPending) return
        val current = set ?: return
        val name = setName ?: return
        ConfirmDialog.showConfirmDialog(this, R.string.confirm_save_dialog, object : Callback<Any?>() {
            override fun onDone(result: Any?) = saveConfirmed(current, name)
            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun saveConfirmed(currentSet: Set, currentName: String, replaceExisting: Boolean = false) {
        if (work.busy || exportController.isPending || cardDialog.isShowing || isDestroyed) return
        val token = draftToken
        val snapshot = snapshotState(null)
        work.run({
            token?.let { draftStore.capture(it, currentSet, snapshot) }
            var failure: Exception? = null
            setsManager.save(currentSet, currentName, replaceExisting, object : Callback<Any?>() {
                override fun onDone(result: Any?) = Unit
                override fun onFail(error: Exception?) { failure = error ?: java.io.IOException("Save failed") }
            })
            failure?.let { throw it }
        }, {
                draftFinished = true
                cardDialog.dismiss()
                // Cleanup cannot turn a committed save into an error. An abandoned completion
                // retains its durable draft so a recreated editor can recover it.
                work.run({ token?.let { draftStore.discard(it) } }, {}, {})
                setResult(RESULT_OK)
                finish()
        }, { error ->
                if (error is SetAlreadyExistsException) {
                    ConfirmDialog.showConfirmDialog(this@SetEditActivity, R.string.confirm_replace_set, object : Callback<Any?>() {
                        override fun onDone(result: Any?) = saveConfirmed(currentSet, currentName, true)
                        override fun onFail(error: Exception?) = Unit
                    })
                } else Toast.makeText(this@SetEditActivity, R.string.set_save_error, Toast.LENGTH_LONG).show()
        })
    }

    private fun close() {
        if (work.busy || exportController.isPending) return
        ConfirmDialog.showConfirmDialog(this, R.string.confirm_exit_dialog, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                discardDraft()
                setResult(RESULT_OK)
                finish()
            }
            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun captureDraft(): Boolean {
        if (work.busy) return true
        val token = draftToken ?: return true
        if (draftFinished) return true
        val current = set ?: return true
        return try {
            val state = snapshotState(cardDialog.captureState(draftStore.mediaDirectory(token)))
            draftStore.capture(token, current, state)
            true
        } catch (_: Exception) {
            Toast.makeText(this, R.string.set_save_error, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun snapshotState(dialog: JSONObject?): JSONObject = JSONObject().put("name", setName).put("page", page)
        .put("position", editedPosition).put("nextPage", editedNextPage).put("dialog", dialog ?: JSONObject.NULL)

    override fun onSaveInstanceState(outState: Bundle) {
        exportController.saveState(outState)
        captureDraft()
        draftToken?.takeUnless { draftFinished }?.let { outState.putString(DRAFT_TOKEN, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (::cardDialog.isInitialized) cardDialog.onHostStop()
        // Final atomic snapshot is synchronous: lifecycle cancellation cannot lose process-death state.
        // Media snapshots are cached by identity, so unchanged media is not rewritten here.
        captureDraft()
        super.onStop()
    }

    override fun onDestroy() {
        if (::cardDialog.isInitialized) cardDialog.release()
        set?.close()
        super.onDestroy()
    }

    private fun discardDraft() {
        draftFinished = true
        cardDialog.dismiss()
        draftToken?.let { draftStore.discard(it) }
    }

    companion object {
        const val EXTRA_FILE = "file"
        private const val DRAFT_TOKEN = "editorDraftToken"
    }
}
