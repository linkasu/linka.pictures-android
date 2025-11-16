package su.linka.pictures.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.GridView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.analytics.FirebaseAnalytics
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.Callback
import su.linka.pictures.R
import su.linka.pictures.SetManifest
import su.linka.pictures.SetsAdapter
import su.linka.pictures.SetsManager
import su.linka.pictures.components.InputDialog
import su.linka.pictures.components.ParentPasswordDialog
import su.linka.pictures.components.SetContextDialog
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var setsList: GridView
    private lateinit var adapter: SetsAdapter
    private lateinit var analytics: FirebaseAnalytics
    private lateinit var setsManager: SetsManager

    private val activityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadSetsList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        analytics = FirebaseAnalytics.getInstance(this)
        supportActionBar?.setTitle(R.string.your_sets)

        setsManager = SetsManager(this)
        adapter = SetsAdapter(this)

        setsList = findViewById(R.id.sets_list)
        setsList.adapter = adapter

        loadDefaultSets()
        loadSetsList()

        setsList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { manifest ->
                openSet(manifest)
            }
        }

        setsList.onItemLongClickListener = AdapterView.OnItemLongClickListener { view, _, position, _ ->
            val manifest = adapter.getItem(position) ?: return@OnItemLongClickListener true
            SetContextDialog.show(view.context, manifest.name, object : Callback<Int>() {
                override fun onDone(result: Int) {
                    when (result) {
                        SetContextDialog.OPEN -> openSet(manifest)
                        SetContextDialog.EDIT -> editSet(manifest)
                        SetContextDialog.RENAME -> renameSet(manifest)
                        SetContextDialog.DELETE -> deleteSet(manifest)
                    }
                }

                override fun onFail(error: Exception?) = Unit
            })
            true
        }

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            createSet()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.settings) {
            ParentPasswordDialog.showDialog(this, object : Callback<Any?>() {
                override fun onDone(result: Any?) {
                    analytics.logEvent(AnalyticsEvents.OPEN_SETTINGS, null)
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }

                override fun onFail(error: Exception?) = Unit
            })
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.shutdown()
    }

    private fun renameSet(manifest: SetManifest) {
        InputDialog.showDialog(this, R.string.rename, object : Callback<String>() {
            override fun onDone(result: String) {
                setsManager.rename(manifest, result)
                loadSetsList()
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun deleteSet(manifest: SetManifest) {
        su.linka.pictures.components.ConfirmDialog.showConfirmDialog(this, R.string.delete, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                setsManager.delete(manifest)
                loadSetsList()
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun openSet(manifest: SetManifest) {
        analytics.logEvent(AnalyticsEvents.OPEN_SET, null)
        val intent = Intent(this, GridActivity::class.java).apply {
            putExtra(GridActivity.EXTRA_FILE, manifest.name)
        }
        startActivity(intent)
    }

    private fun editSet(manifest: SetManifest) {
        analytics.logEvent(AnalyticsEvents.EDIT_SET, null)
        val intent = Intent(this, SetEditActivity::class.java).apply {
            putExtra(SetEditActivity.EXTRA_FILE, manifest.name)
        }
        startActivity(intent)
    }

    private fun createSet() {
        analytics.logEvent(AnalyticsEvents.CREATE_SET, null)
        ParentPasswordDialog.showDialog(this, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                val intent = Intent(this@MainActivity, SetEditActivity::class.java)
                activityLauncher.launch(intent)
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun loadSetsList() {
        val sets = setsManager.getSets()
        adapter.clear()
        adapter.addAll(sets.toList())
        adapter.notifyDataSetChanged()
    }

    private fun loadDefaultSets() {
        try {
            setsManager.loadDefaultSets()
        } catch (error: IOException) {
            error.printStackTrace()
            Toast.makeText(this, R.string.copy_assets_error, Toast.LENGTH_LONG).show()
        }
    }

}
