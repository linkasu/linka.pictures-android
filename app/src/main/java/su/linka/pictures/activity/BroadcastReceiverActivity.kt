package su.linka.pictures.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.Callback
import su.linka.pictures.R
import su.linka.pictures.SetsManager
import su.linka.pictures.Telemetry
import su.linka.pictures.ArchiveLimits
import su.linka.pictures.BoundedStreams
import su.linka.pictures.Utils
import su.linka.pictures.components.ConfirmDialog
import su.linka.pictures.resolveImportedSetFile
import su.linka.pictures.components.ParentPasswordDialog
import java.io.IOException

class BroadcastReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data
            if (uri != null) {
                ParentPasswordDialog.run(this, onDenied = { finish() }) { askCopy(uri) }
            } else {
                finish()
            }
        } else {
            finish()
        }
    }

    private fun askCopy(uri: Uri) {
        ConfirmDialog.showConfirmDialog(this, R.string.add_set_to_library, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                copy(uri)
            }

            override fun onFail(error: Exception?) {
                finish()
            }
        })
    }

    private fun copy(uri: Uri) {
        try {
            val destination = resolveImportedSetFile(SetsManager(this).getSetsDirectory(), uri.lastPathSegment)
            contentResolver.openInputStream(uri)?.use { input ->
                Utils.copy(BoundedStreams.limit(input, ArchiveLimits().maxInputBytes), destination)
            } ?: throw IOException("Unable to open input stream")
        } catch (error: Exception) {
            if (error !is IOException && error !is SecurityException && error !is IllegalArgumentException) {
                throw error
            }
            error.printStackTrace()
            Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Telemetry.logEvent(AnalyticsEvents.ADD_SET, null)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }
}
