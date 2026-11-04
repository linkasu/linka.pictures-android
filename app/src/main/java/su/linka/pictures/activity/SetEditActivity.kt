package su.linka.pictures.activity

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import net.lingala.zip4j.exception.ZipException
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.SetAlreadyExistsException
import su.linka.pictures.SetsManager
import su.linka.pictures.SetExportController
import su.linka.pictures.Telemetry
import su.linka.pictures.EditorDraftStore
import org.json.JSONObject
import java.io.File
import su.linka.pictures.components.CardGrid
import su.linka.pictures.components.ConfirmDialog
import su.linka.pictures.components.EditCardDialog
import su.linka.pictures.components.EditCardGrid
import su.linka.pictures.components.InputDialog
import su.linka.pictures.shared.GridDimensions

class SetEditActivity : AppCompatActivity() {

    private lateinit var setsManager: SetsManager
    private lateinit var grid: EditCardGrid
    private lateinit var rowsCountEditText: EditText
    private lateinit var columnsEditText: EditText
    private lateinit var withoutSpaceCheckbox: CheckBox
    private lateinit var cardDialog: EditCardDialog

    private var set: Set? = null
    private var setName: String? = null
    private lateinit var draftStore: EditorDraftStore
    private var draftToken: String? = null
    private var editedPosition = 0
    private var editedNextPage = false
    private var draftFinished = false
    private val exportController = SetExportController(this,
        onSuccess = { Toast.makeText(this, R.string.set_export_success, Toast.LENGTH_LONG).show() },
        onError = { Toast.makeText(this, R.string.set_export_error, Toast.LENGTH_LONG).show() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportController.restoreState(savedInstanceState)
        setContentView(R.layout.activity_set_edit)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        cardDialog = EditCardDialog(this)
        setsManager = SetsManager(this)
        draftStore = EditorDraftStore(this)
        cardDialog.onChooseImage = { captureDraft() }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.create_set)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                close()
            }
        })

        grid = findViewById(R.id.card_grid)
        grid.setCardSelectListener(CardGrid.OnCardSelectListener { card, pos ->
            onCardSelected(card, pos, false)
        })

        rowsCountEditText = findViewById(R.id.rows_count)
        columnsEditText = findViewById(R.id.columns_count)
        withoutSpaceCheckbox = findViewById(R.id.without_space_checkbox)

        findViewById<android.view.View>(R.id.change_grid_size_button).setOnClickListener {
            val currentSet = set ?: return@setOnClickListener
            val manifest = currentSet.getManifest()
            val rows = rowsCountEditText.text.toString().toIntOrNull()
            val columns = columnsEditText.text.toString().toIntOrNull()
            if (rows == null || columns == null || runCatching { GridDimensions(rows, columns) }.isFailure) {
                Toast.makeText(this, R.string.invalid_grid_dimensions, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            manifest.rows = rows
            manifest.columns = columns
            grid.jumpToPage(0)
            grid.setSet(currentSet)
            grid.refresh()
            Telemetry.logEvent(AnalyticsEvents.RESIZE_GRID, null)
        }

        withoutSpaceCheckbox.setOnClickListener {
            set?.getManifest()?.withoutSpace = withoutSpaceCheckbox.isChecked
            Telemetry.logEvent(AnalyticsEvents.SET_WITHOUT_SPACE, null)
        }

        findViewById<android.view.View>(R.id.prev_button).setOnClickListener {
            grid.prevPage()
        }

        findViewById<android.view.View>(R.id.next_button).setOnClickListener { view ->
            val hasNext = grid.nextPage()
            if (!hasNext) {
                ConfirmDialog.showConfirmDialog(view.context, R.string.confirm_page_creation, object : Callback<Any?>() {
                    override fun onDone(result: Any?) {
                        val nextPageIndex = (grid.currentPage() + 1) * grid.getPageSize()
                        onCardSelected(null, nextPageIndex, true)
                    }

                    override fun onFail(error: Exception?) = Unit
                })
            }
        }

        val restored = draftStore.restore(savedInstanceState?.getString(DRAFT_TOKEN))
        if (restored != null) {
            try {
                restoreDraft(restored)
            } catch (_: Exception) {
                draftFinished = true // Preserve the unreadable draft for recovery; do not checkpoint partial UI.
                Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        // Never silently replace a missing/corrupt unsaved draft with the original archive.
        if (savedInstanceState?.containsKey(DRAFT_TOKEN) == true) {
            Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setName = intent?.extras?.getString(SetEditActivity.EXTRA_FILE)
        if (setName == null) {
            showEnterTitleDialog()
        } else {
            loadFromFile()
        }
    }

    private fun restoreDraft(draft: EditorDraftStore.Draft) {
        val state = draft.state
        val name = state.getString("name")
        require(name.isNotEmpty() && File(name).name == name)
        draftToken = draft.token
        set = draft.set
        setName = name
        loadSet()
        grid.jumpToPage(state.optInt("page"))
        rowsCountEditText.setText(state.optString("rows", rowsCountEditText.text.toString()))
        columnsEditText.setText(state.optString("columns", columnsEditText.text.toString()))
        state.optJSONObject("dialog")?.let { dialogState ->
            editedPosition = state.getInt("position")
            require(editedPosition in 0 until su.linka.pictures.shared.BoardLimits.MAX_CARDS)
            require(editedPosition.toLong() in 0..grid.getPagesCount().toLong() * grid.getPageSize())
            editedNextPage = state.optBoolean("nextPage")
            cardDialog.restoreState(draft.set, dialogState, draftStore.mediaDirectory(draft.token))
            bindCardCallback()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        cardDialog.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.set_edit_activity_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.export_menu_button -> {
                if (set != null) exportController.export(setName ?: "set.linka") { checkNotNull(set) }
                true
            }
            R.id.save_menu_button -> {
                save()
                true
            }
            android.R.id.home -> {
                close()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onCardSelected(card: Card?, pos: Int, nextPage: Boolean) {
        val currentSet = set ?: return
        if (pos !in 0 until su.linka.pictures.shared.BoardLimits.MAX_CARDS) {
            Toast.makeText(this, R.string.card_limit_reached, Toast.LENGTH_LONG).show()
            return
        }
        editedPosition = pos
        editedNextPage = nextPage
        cardDialog.show(currentSet, if (card != null && card.cardType < 3) card else null)
        bindCardCallback()
    }

    private fun bindCardCallback() {
        val pos = editedPosition
        val nextPage = editedNextPage
        cardDialog.setCallback(object : Callback<Card>() {
            override fun onDone(result: Card) {
                addCard(pos, result)
                if (nextPage) {
                    grid.nextPage()
                }
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun addCard(pos: Int, card: Card) {
        val currentSet = set ?: return
        Telemetry.logEvent(AnalyticsEvents.ADD_CARD, null)
        currentSet.addCard(pos, card)
        grid.refresh()
    }

    private fun showEnterTitleDialog() {
        InputDialog.showDialog(this, R.string.set_name, object : Callback<String>() {
            override fun onDone(result: String) {
                setName = "$result.linka"
                set = setsManager.createSet()
                loadSet()
            }

            override fun onFail(error: Exception?) {
                finish()
            }
        })
    }

    private fun loadFromFile() {
        try {
            set = setName?.let { setsManager.getSet(it) }
        } catch (error: ZipException) {
            error.printStackTrace()
            Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadSet()
    }

    private fun loadSet() {
        if (draftToken == null) {
            try {
                val draft = draftStore.create(set ?: return)
                draftToken = draft.token
                set = draft.set
            } catch (_: Exception) {
                Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        val currentSet = set ?: return
        val manifest = currentSet.getManifest()
        withoutSpaceCheckbox.isChecked = manifest.withoutSpace
        grid.setSet(currentSet)
        rowsCountEditText.setText(manifest.rows.toString())
        columnsEditText.setText(manifest.columns.toString())
    }

    private fun save() {
        val currentSet = set ?: return
        val currentName = setName ?: return
        ConfirmDialog.showConfirmDialog(this, R.string.confirm_save_dialog, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                currentSet.getManifest().withoutSpace = withoutSpaceCheckbox.isChecked
                saveConfirmed(currentSet, currentName)
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun saveConfirmed(currentSet: Set, currentName: String, replaceExisting: Boolean = false) {
        setsManager.save(currentSet, currentName, replaceExisting, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                discardDraft()
                setResult(RESULT_OK)
                finish()
            }

            override fun onFail(error: Exception?) {
                if (error is SetAlreadyExistsException) {
                    ConfirmDialog.showConfirmDialog(this@SetEditActivity, R.string.confirm_replace_set, object : Callback<Any?>() {
                        override fun onDone(result: Any?) { saveConfirmed(currentSet, currentName, true) }
                        override fun onFail(error: Exception?) = Unit
                    })
                    return
                }
                Toast.makeText(this@SetEditActivity, R.string.set_save_error, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun close() {
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
        val token = draftToken ?: return true
        if (draftFinished) return true
        val currentSet = set ?: return true
        return try {
            val state = JSONObject().put("name", setName).put("page", grid.currentPage())
                .put("rows", rowsCountEditText.text.toString())
                .put("columns", columnsEditText.text.toString())
                .put("position", editedPosition).put("nextPage", editedNextPage)
                .put("dialog", cardDialog.captureState(draftStore.mediaDirectory(token)) ?: JSONObject.NULL)
            draftStore.capture(token, currentSet, state)
            true
        } catch (_: Exception) {
            Toast.makeText(this, R.string.set_save_error, Toast.LENGTH_LONG).show()
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        exportController.saveState(outState)
        captureDraft()
        draftToken?.takeUnless { draftFinished }?.let { outState.putString(DRAFT_TOKEN, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (::cardDialog.isInitialized) cardDialog.onHostStop()
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
