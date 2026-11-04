package su.linka.pictures

import android.net.Uri
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val operation by lazy { ViewModelProvider(activity)[Operation::class.java] }
    private var pendingToken: String?
        get() = operation.token
        set(value) { operation.token = value }
    private var authorizing by mutableStateOf(false)
    private val work = ActivityWork(activity)
    val isPending: Boolean get() = authorizing || work.busy || operation.preparing ||
        operation.writing || operation.awaitingCompletion || pendingToken != null

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                operation.completion.collect { result ->
                    if (result != null && !activity.isDestroyed && operation.completion.compareAndSet(result, null)) {
                        operation.awaitingCompletion = false
                        if (result.error == null) onSuccess() else onError(result.error)
                    }
                }
            }
        }
    }

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
                if (activity.isDestroyed) return
                authorizing = false
                val state = operation
                state.preparing = true
                work.run({
                    try { prepare() } finally { state.finishPreparing() }
                }, { archive ->
                    pendingToken = archive.nameWithoutExtension
                    val name = File(fileName).name.let {
                        if (it.endsWith(".linka")) it else "$it.linka"
                    }
                    launcher.launch(name)
                }, { error ->
                    clearPending()
                    onError(error)
                }, abandoned = { it.delete() })
            }

            override fun onFail(error: Exception?) {
                if (activity.isDestroyed) return
                authorizing = false
                if (error == null) onCancelled() else onError(error)
            }
        })
    }

    fun saveState(outState: Bundle) {
        outState.remove(STATE_KEY)
        outState.remove(STATE_WRITING)
        pendingToken?.let { outState.putString(STATE_KEY, it) }
        if (operation.writing) outState.putBoolean(STATE_WRITING, true)
    }

    fun restoreState(savedState: Bundle?) {
        // A retained operation is authoritative; the Bundle may describe an already consumed result.
        if (operation.restored) return
        operation.restored = true
        val token = savedState?.getString(STATE_KEY) ?: return
        if (!TOKEN.matches(token)) {
            onError(IOException("Invalid pending export token"))
            return
        }
        pendingToken = token
        if (savedState.getBoolean(STATE_WRITING)) {
            clearPending()
            operation.finish(IOException("Export interrupted by process death"))
            return
        }
        if (!pendingFile()!!.isFile) {
            // Keep the token until the picker result: never mistake missing cache for success.
            onError(IOException("Prepared export is no longer available"))
        }
    }

    internal fun complete(uri: Uri?) {
        if (work.busy || operation.writing || operation.awaitingCompletion || activity.isDestroyed) return
        val archive = pendingFile()
        if (uri == null) {
            clearPending()
            onCancelled()
            return
        }
        operation.write(activity.applicationContext, archive, uri)
    }

    /** Retains only operation data and application-owned IO, never a host or its callbacks. */
    internal class Operation : ViewModel() {
        var token by mutableStateOf<String?>(null)
        var preparing by mutableStateOf(false)
        var writing by mutableStateOf(false)
        var awaitingCompletion by mutableStateOf(false)
        var restored = false
        val completion = MutableStateFlow<Completion?>(null)

        fun finishPreparing() { viewModelScope.launch { preparing = false } }

        fun finish(error: Exception?) {
            token = null
            writing = false
            awaitingCompletion = true
            completion.value = Completion(error)
        }

        fun write(context: Context, archive: File?, uri: Uri) {
            if (writing) return
            writing = true
            viewModelScope.launch {
                val error = withContext(Dispatchers.IO + NonCancellable) {
                    try {
                        if (archive == null || !archive.isFile) throw IOException("Prepared export is no longer available")
                        // Open input first: missing private cache must never truncate the provider target.
                        archive.inputStream().use { input ->
                            val output = context.contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException("Cannot open export destination")
                            output.use { input.copyTo(it) }
                        }
                        null
                    } catch (error: Exception) { error }
                    finally { archive?.delete() }
                }
                finish(error)
            }
        }
    }

    internal data class Completion(val error: Exception?)

    private fun pendingFile(): File? = pendingToken?.let { File(activity.cacheDir, "exports/$it.linka") }

    private fun clearPending() {
        pendingFile()?.delete()
        pendingToken = null
    }

    companion object {
        private const val STATE_KEY = "preparedSetExport"
        private const val STATE_WRITING = "writingSetExport"
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
