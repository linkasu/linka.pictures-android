package su.linka.pictures.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.Callback
import su.linka.pictures.R
import su.linka.pictures.SetsManager
import su.linka.pictures.Telemetry
import su.linka.pictures.components.ParentPasswordDialog
import su.linka.pictures.SetAlreadyExistsException
import su.linka.pictures.components.ConfirmDialog
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

    private fun copy(uri: Uri, replaceExisting: Boolean = false, confirmedName: String? = null) {
        val setsManager = SetsManager(this)
        try {
            val fileName = confirmedName ?: importedFileName(uri) ?: throw IOException("Missing import file name")
            contentResolver.openInputStream(uri)?.use { input ->
                setsManager.importSet(input, fileName, replaceExisting)
            } ?: throw IOException("Unable to open input stream")
        } catch (error: SetAlreadyExistsException) {
            ConfirmDialog.showConfirmDialog(this, R.string.confirm_replace_set, object : Callback<Any?>() {
                override fun onDone(result: Any?) { copy(uri, true, error.setName) }
                override fun onFail(error: Exception?) { finish() }
            })
            return
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

    private fun importedFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                    return cursor.getString(column)
                }
            }
        }
        return uri.lastPathSegment
    }
}
