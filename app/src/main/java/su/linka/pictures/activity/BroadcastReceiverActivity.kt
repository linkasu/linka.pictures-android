package su.linka.pictures.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import su.linka.pictures.AnalyticsEvents
import su.linka.pictures.Callback
import su.linka.pictures.R
import su.linka.pictures.SetsManager
import su.linka.pictures.Utils
import su.linka.pictures.components.ConfirmDialog
import java.io.File
import java.io.IOException

class BroadcastReceiverActivity : Activity() {

    private lateinit var analytics: FirebaseAnalytics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analytics = FirebaseAnalytics.getInstance(this)
        val action = intent?.action
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data
            if (uri != null) {
                askCopy(uri)
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
        val setsManager = SetsManager(this)
        val fileName = uri.lastPathSegment ?: run {
            finish()
            return
        }
        val destination = File(setsManager.getSetsDirectory(), fileName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                Utils.copy(input, destination)
            } ?: throw IOException("Unable to open input stream")
        } catch (error: IOException) {
            error.printStackTrace()
            finish()
            return
        }
        analytics.logEvent(AnalyticsEvents.ADD_SET, null)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }
}
