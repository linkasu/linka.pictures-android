package su.linka.pictures

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/** Runs before any Activity can create a workspace; durable drafts are outside this cache root. */
internal object WorkspaceCleanup {
    private val token = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun onProcessStart(context: Context) {
        try {
            val root = File(context.cacheDir, "workspace")
            if (root.canonicalFile != File(context.cacheDir.canonicalFile, "workspace")) return
            root.listFiles()?.filter { token.matches(it.name) }?.forEach(::deleteScratch)
        } catch (error: IOException) {
            Log.w("WorkspaceCleanup", "Could not reclaim an abandoned workspace", error)
        }
    }

    private fun deleteScratch(file: File) {
        if (file.canonicalFile == File(file.parentFile!!.canonicalFile, file.name) && file.isDirectory) {
            file.listFiles()?.forEach(::deleteScratch)
        }
        file.delete()
    }
}
