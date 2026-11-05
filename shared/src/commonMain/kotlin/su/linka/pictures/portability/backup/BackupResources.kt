package su.linka.pictures.portability.backup

import su.linka.pictures.application.ports.*
import su.linka.pictures.portability.zip.value

internal class BackupResources(private val files: PlatformFilesPort) {
    private val temporary = mutableListOf<TemporaryFile>()
    val owned = mutableListOf<AutoCloseable>()
    fun temporary(name: String): TemporaryFile = files.createTemporaryFile(name, "application/octet-stream").value().also(temporary::add)
    fun transfer(file: TemporaryFile) { check(temporary.remove(file)) }
    fun cleanup(throwOnFailure: Boolean, keep: TemporaryFile? = null) {
        var failure: Exception? = null
        while (owned.isNotEmpty()) {
            val handle = owned.removeAt(owned.lastIndex)
            try { handle.close() } catch (error: Exception) { if (failure == null) failure = error }
        }
        for (index in temporary.lastIndex downTo 0) {
            if (temporary[index] === keep) continue
            val file = temporary.removeAt(index)
            try { files.discard(file) } catch (error: Exception) { if (failure == null) failure = error }
        }
        if (throwOnFailure) failure?.let { throw it }
    }
}
