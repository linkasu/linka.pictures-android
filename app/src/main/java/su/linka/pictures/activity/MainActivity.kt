package su.linka.pictures.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.ActivityWork
import su.linka.pictures.Callback
import su.linka.pictures.R
import su.linka.pictures.SetManifest
import su.linka.pictures.SetExportController
import su.linka.pictures.SetsManager
import su.linka.pictures.Telemetry
import su.linka.pictures.TelemetryCollectionState
import su.linka.pictures.components.InputDialog
import su.linka.pictures.components.ParentPasswordDialog
import su.linka.pictures.components.SetImage
import su.linka.pictures.ui.*

class MainActivity : AppCompatActivity() {

    internal var sets by mutableStateOf<List<SetSummary>>(emptyList())
        private set
    private var manifests by mutableStateOf<List<SetManifest>>(emptyList())
    private var optionsFor by mutableStateOf<String?>(null)
    private lateinit var setsManager: SetsManager
    private val work = ActivityWork(this)
    private var previews by mutableStateOf<Map<String, java.io.File?>>(emptyMap())
    private var privacyDialog: AlertDialog? = null
    private var pendingAnalyticsChoice = false
    private val exportController = SetExportController(this,
        onSuccess = { Toast.makeText(this, R.string.set_export_success, Toast.LENGTH_LONG).show() },
        onError = { Toast.makeText(this, R.string.set_export_error, Toast.LENGTH_LONG).show() })

    private val activityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadSetsList()
        }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) activityLauncher.launch(Intent(this, BroadcastReceiverActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportController.restoreState(savedInstanceState)
        pendingAnalyticsChoice = savedInstanceState?.getBoolean(KEY_PENDING_ANALYTICS_CHOICE)
            ?: (Telemetry.privacyState() == TelemetryCollectionState.ENABLED)
        setsManager = SetsManager(this)
        loadSetsList(defaults = true)
        setContent {
            PicturesTheme {
                LibraryScreen(sets, work.busy || exportController.isPending, null, ::onAction, image = { summary, modifier ->
                    SetImage(previews[summary.name], modifier)
                }, optionsFor = optionsFor)
            }
        }
        showPrivacyNoticeIfNeeded()
    }
    internal fun onAction(action: LibraryAction) {
        if (isDestroyed || work.busy || exportController.isPending) return
        optionsFor = null
        fun manifest(summary: SetSummary) = manifests.firstOrNull { it.name == summary.name }
        when (action) {
            is LibraryAction.Options -> optionsFor = action.set?.name
            is LibraryAction.Open -> manifest(action.set)?.let(::openSet)
            is LibraryAction.Edit -> manifest(action.set)?.let(::editSet)
            is LibraryAction.Rename -> manifest(action.set)?.let(::renameSet)
            is LibraryAction.Delete -> manifest(action.set)?.let(::deleteSet)
            is LibraryAction.Export -> manifest(action.set)?.let(exportController::export)
            LibraryAction.Create -> createSet()
            LibraryAction.Import -> ParentPasswordDialog.run(this) { importLauncher.launch(arrayOf("*/*")) }
            LibraryAction.Settings -> {
                Telemetry.logEvent(AnalyticsEvents.OPEN_SETTINGS, null)
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PENDING_ANALYTICS_CHOICE, pendingAnalyticsChoice)
        exportController.saveState(outState)
        super.onSaveInstanceState(outState)
    }


    private fun renameSet(manifest: SetManifest) = ParentPasswordDialog.run(this) {
        InputDialog.showDialog(this, R.string.rename, object : Callback<String>() {
            override fun onDone(result: String) {
                renameConfirmed(manifest, result)
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun renameConfirmed(manifest: SetManifest, name: String, replaceExisting: Boolean = false) {
        if (isDestroyed || work.busy || exportController.isPending) return
        work.run({ setsManager.rename(manifest, name, replaceExisting) }, { loadSetsList() }, { error ->
          if (error is su.linka.pictures.SetAlreadyExistsException) {
            su.linka.pictures.components.ConfirmDialog.showConfirmDialog(this, R.string.confirm_replace_set, object : Callback<Any?>() {
                override fun onDone(result: Any?) { renameConfirmed(manifest, name, true) }
                override fun onFail(error: Exception?) = Unit
            })
          } else {
            android.widget.Toast.makeText(this, R.string.set_save_error, android.widget.Toast.LENGTH_LONG).show()
          }
        })
    }

    private fun deleteSet(manifest: SetManifest) = ParentPasswordDialog.run(this) {
        su.linka.pictures.components.ConfirmDialog.showConfirmDialog(this, R.string.delete, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                if (isDestroyed || work.busy || exportController.isPending) return
                work.run({ setsManager.delete(manifest) }, { loadSetsList() }, {
                    Toast.makeText(this@MainActivity, R.string.set_save_error, Toast.LENGTH_LONG).show()
                })
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun openSet(manifest: SetManifest) {
        if (isDestroyed) return
        Telemetry.logEvent(AnalyticsEvents.OPEN_SET, null)
        val intent = Intent(this, GridActivity::class.java).apply {
            putExtra(GridActivity.EXTRA_FILE, manifest.name)
        }
        startActivity(intent)
    }

    private fun editSet(manifest: SetManifest) = ParentPasswordDialog.run(this) {
        if (isDestroyed) return@run
        Telemetry.logEvent(AnalyticsEvents.EDIT_SET, null)
        val intent = Intent(this, SetEditActivity::class.java).apply {
            putExtra(SetEditActivity.EXTRA_FILE, manifest.name)
        }
        activityLauncher.launch(intent)
    }

    private fun createSet() {
        Telemetry.logEvent(AnalyticsEvents.CREATE_SET, null)
        ParentPasswordDialog.showDialog(this, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                if (isDestroyed) return
                val intent = Intent(this@MainActivity, SetEditActivity::class.java)
                activityLauncher.launch(intent)
            }

            override fun onFail(error: Exception?) = Unit
        })
    }

    private fun loadSetsList(defaults: Boolean = false) {
        work.run({
            val defaultsError = if (defaults) runCatching { setsManager.loadDefaultSets() }.exceptionOrNull() else null
            val loaded = setsManager.getSets().toList()
            val images = loaded.mapNotNull { manifest ->
                manifest.getDefaultBitmap()?.let { path ->
                    runCatching { manifest.name to setsManager.getSetImage(manifest, path) }.getOrNull()
                }
            }.toMap()
            Triple(loaded, images, defaultsError)
        }, { (loaded, images, defaultsError) ->
            manifests = loaded
            previews = images
            sets = loaded.map { SetSummary(it.name, it.getDefaultBitmap()) }
            if (defaultsError != null) Toast.makeText(this, R.string.copy_assets_error, Toast.LENGTH_LONG).show()
        }, { error ->
            error.printStackTrace()
            Toast.makeText(this, R.string.copy_assets_error, Toast.LENGTH_LONG).show()
        })
    }

    private fun showPrivacyNoticeIfNeeded() {
        val privacyState = Telemetry.privacyState()
        if (!privacyState.requiresNotice || privacyDialog?.isShowing == true) return

        val content = layoutInflater.inflate(R.layout.telemetry_privacy_dialog, null, false)
        val analyticsChoice = content.findViewById<CheckBox>(R.id.telemetry_analytics_choice)
        analyticsChoice.isChecked = pendingAnalyticsChoice
        analyticsChoice.setOnCheckedChangeListener { _, isChecked ->
            pendingAnalyticsChoice = isChecked
        }

        privacyDialog = AlertDialog.Builder(this)
            .setTitle(R.string.privacy_notice_title)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                Telemetry.setAnalyticsEnabled(pendingAnalyticsChoice)
            }
            .setNegativeButton(R.string.privacy_not_now, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { privacyDialog = null }
                dialog.show()
            }
    }

    companion object {
        private const val KEY_PENDING_ANALYTICS_CHOICE = "pending_analytics_choice"
    }

}
