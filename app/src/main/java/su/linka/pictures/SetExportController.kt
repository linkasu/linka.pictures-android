package su.linka.pictures

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import su.linka.pictures.components.ParentPasswordDialog
import java.io.File
import java.io.IOException

/** Construct before STARTED; restore in onCreate and save in onSaveInstanceState. */
class SetExportController(
    private val activity: ComponentActivity,
    private val onSuccess: () -> Unit,
    private val onError: (Exception) -> Unit,
    private val onCancelled: () -> Unit = {}
) {
    private var pendingToken: String? = null
    private var authorizing = false
    val isPending: Boolean get() = authorizing || pendingToken != null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.linka")
    ) { uri -> complete(uri) }

    /** The source is evaluated only after parent approval; never save it to the library. */
    fun export(fileName: String, source: () -> Set) {
        requestExport(fileName) { SetsManager(activity).prepareExport(source()) }
    }

    /** Library export owns and removes its extraction workspace; editor sources stay untouched. */
    fun export(manifest: SetManifest) {
        requestExport(manifest.name) {
            val manager = SetsManager(activity)
            val source = manager.getSet(manifest.name)
            try {
                manager.prepareExport(source)
            } finally {
                source.close()
            }
        }
    }

    private fun requestExport(fileName: String, prepare: () -> File) {
        if (isPending) return
        authorizing = true
        ParentPasswordDialog.showDialog(activity, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                authorizing = false
                try {
                    val archive = prepare()
                    pendingToken = archive.nameWithoutExtension
                    val name = File(fileName).name.let {
                        if (it.endsWith(".linka")) it else "$it.linka"
                    }
                    launcher.launch(name)
                } catch (error: Exception) {
                    clearPending()
                    onError(error)
                }
            }

            override fun onFail(error: Exception?) {
                authorizing = false
                if (error == null) onCancelled() else onError(error)
            }
        })
    }

    fun saveState(outState: Bundle) {
        pendingToken?.let { outState.putString(STATE_KEY, it) }
    }

    fun restoreState(savedState: Bundle?) {
        val token = savedState?.getString(STATE_KEY) ?: return
        if (!TOKEN.matches(token)) {
            onError(IOException("Invalid pending export token"))
            return
        }
        pendingToken = token
        if (!pendingFile()!!.isFile) {
            // Keep the token until the picker result: never mistake missing cache for success.
            onError(IOException("Prepared export is no longer available"))
        }
    }

    internal fun complete(uri: Uri?) {
        val archive = pendingFile()
        if (uri == null) {
            clearPending()
            onCancelled()
            return
        }
        val error = try {
            if (archive == null || !archive.isFile) throw IOException("Prepared export is no longer available")
            // Open input first: missing private cache must never truncate the provider target.
            archive.inputStream().use { input ->
                val output = activity.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Cannot open export destination")
                output.use { input.copyTo(it) }
            }
            null
        } catch (failure: Exception) {
            failure
        }
        clearPending()
        if (error == null) onSuccess() else onError(error)
    }

    private fun pendingFile(): File? = pendingToken?.let { File(activity.cacheDir, "exports/$it.linka") }

    private fun clearPending() {
        pendingFile()?.delete()
        pendingToken = null
    }

    companion object {
        private const val STATE_KEY = "preparedSetExport"
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
