package su.linka.pictures.activity

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.analytics.FirebaseAnalytics
import net.lingala.zip4j.exception.ZipException
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.SetsManager
import su.linka.pictures.components.CardGrid
import su.linka.pictures.components.ConfirmDialog
import su.linka.pictures.components.EditCardDialog
import su.linka.pictures.components.EditCardGrid
import su.linka.pictures.components.InputDialog

class SetEditActivity : AppCompatActivity() {

    private lateinit var setsManager: SetsManager
    private lateinit var grid: EditCardGrid
    private lateinit var rowsCountEditText: EditText
    private lateinit var columnsEditText: EditText
    private lateinit var withoutSpaceCheckbox: CheckBox
    private lateinit var cardDialog: EditCardDialog
    private lateinit var analytics: FirebaseAnalytics

    private var set: Set? = null
    private var setName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_edit)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        analytics = FirebaseAnalytics.getInstance(this)
        cardDialog = EditCardDialog(this)
        setsManager = SetsManager(this)

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
            val rows = rowsCountEditText.text.toString().toIntOrNull() ?: manifest.rows
            val columns = columnsEditText.text.toString().toIntOrNull() ?: manifest.columns
            manifest.rows = rows
            manifest.columns = columns
            grid.jumpToPage(0)
            grid.setSet(currentSet)
            grid.refresh()
            analytics.logEvent(AnalyticsEvents.RESIZE_GRID, null)
        }

        withoutSpaceCheckbox.setOnClickListener {
            set?.getManifest()?.withoutSpace = withoutSpaceCheckbox.isChecked
            analytics.logEvent(AnalyticsEvents.SET_WITHOUT_SPACE, null)
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

        setName = intent?.extras?.getString(SetEditActivity.EXTRA_FILE)
        if (setName == null) {
            showEnterTitleDialog()
        } else {
            loadFromFile()
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
        cardDialog.show(currentSet, if (card != null && card.cardType < 3) card else null)
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
        analytics.logEvent(AnalyticsEvents.ADD_CARD, null)
        currentSet.addCard(pos, card)
        grid.refresh()
        grid.setSet(currentSet)
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
                setsManager.save(currentSet, currentName, object : Callback<Any?>() {
                    override fun onDone(result: Any?) {
                        setResult(RESULT_OK)
                        finish()
                    }

                    override fun onFail(error: Exception?) {
                        Toast.makeText(this@SetEditActivity, R.string.set_save_error, Toast.LENGTH_LONG).show()
                    }
                })
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun close() {
        ConfirmDialog.showConfirmDialog(this, R.string.confirm_exit_dialog, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                setResult(RESULT_OK)
                finish()
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    companion object {
        const val EXTRA_FILE = "file"
    }
}
