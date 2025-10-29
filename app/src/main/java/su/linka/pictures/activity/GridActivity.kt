package su.linka.pictures.activity

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.analytics.FirebaseAnalytics
import net.lingala.zip4j.exception.ZipException
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.Cookie
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.SetsManager
import su.linka.pictures.components.CardGrid
import su.linka.pictures.components.OutputLine
import su.linka.pictures.components.ParentPasswordDialog

class GridActivity : AppCompatActivity() {

    private lateinit var setsManager: SetsManager
    private lateinit var grid: CardGrid
    private lateinit var outputLine: OutputLine
    private lateinit var nextButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var cookie: Cookie
    private lateinit var analytics: FirebaseAnalytics

    private var set: Set? = null
    private var gridSettings: GridSettings = GridSettings()
    private var fileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid)

        analytics = FirebaseAnalytics.getInstance(this)
        cookie = Cookie(this)
        setsManager = SetsManager(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                close()
            }
        })

        nextButton = findViewById(R.id.next_button)
        prevButton = findViewById(R.id.prev_button)
        outputLine = findViewById(R.id.output_line)
        grid = findViewById(R.id.card_grid)

        fileName = intent?.extras?.getString(EXTRA_FILE)
        if (fileName == null) {
            finish()
            return
        }

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = fileName!!.removeSuffix(".linka")
        }

        try {
            val loadedSet = setsManager.getSet(fileName!!)
            set = loadedSet
            outputLine.setSet(loadedSet)
            grid.setSet(loadedSet)
            grid.setCardSelectListener(CardGrid.OnCardSelectListener { card, _ ->
                onCardSelect(card)
            })
            gridSettings = GridSettings.fromInt(cookie.getSetSettings(fileName!!, DEFAULT_GRID_SETTINGS))
            prepareView()
        } catch (error: ZipException) {
            error.printStackTrace()
            Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.grid_activity_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                close()
                true
            }
            R.id.settings -> {
                ParentPasswordDialog.showDialog(this, object : Callback<Any?>() {
                    override fun onDone(result: Any?) {
                        analytics.logEvent(AnalyticsEvents.OPEN_GRID_SETTINGS, null)
                        showSettings()
                    }

                    override fun onFail(error: Exception?) = Unit
                })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        outputLine.release()
    }

    private fun onCardSelect(card: Card?) {
        if (card != null) {
            analytics.logEvent(AnalyticsEvents.CARD_SELECT, null)
            outputLine.addCard(card)
        }
    }

    private fun showSettings() {
        val settingsView = GridSettingsView(this)
        settingsView.setSettings(gridSettings.copy())

        AlertDialog.Builder(this)
            .setTitle(R.string.grid_activity_settings)
            .setView(settingsView)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                settingsView.commit()
                gridSettings = settingsView.getSettings()
                fileName?.let { cookie.setSetSettings(it, gridSettings.toInt()) }
                prepareView()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun prepareView() {
        outputLine.setDirectMode(!gridSettings.isOutput)
        outputLine.visibility = if (gridSettings.isOutput) View.VISIBLE else View.GONE
        val showPaging = gridSettings.isPagesButtons && grid.getPagesCount() > 1
        prevButton.visibility = if (showPaging) View.VISIBLE else View.GONE
        nextButton.visibility = if (showPaging) View.VISIBLE else View.GONE

        if (showPaging) {
            nextButton.setOnClickListener { grid.nextPage() }
            prevButton.setOnClickListener { grid.prevPage() }
        } else {
            nextButton.setOnClickListener(null)
            prevButton.setOnClickListener(null)
        }
    }

    private fun close() {
        ParentPasswordDialog.showDialog(this, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                finish()
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    companion object {
        const val EXTRA_FILE = "file"
        private const val DEFAULT_GRID_SETTINGS = 3
    }
}
