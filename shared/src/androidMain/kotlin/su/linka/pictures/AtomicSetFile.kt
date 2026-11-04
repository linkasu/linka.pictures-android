package su.linka.pictures

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object AtomicSetFile {
    // shortcut: process-wide lock; use filesystem locking if writes move to multiple processes.
    val lock = Any()

    fun resolve(directory: File, name: String): File {
        val file = resolveImportedSetFile(directory, name)
        if (!name.endsWith(".linka") || name.removeSuffix(".linka").isBlank() || name.any { it.isISOControl() }) {
            throw IOException("Invalid set file name")
        }
        // Inspect the original directory entry: the resolver has already canonicalized its target.
        val entry = File(file.parentFile, name)
        // File.exists() misses dangling symlinks, so also check whether the entry is listed.
        val present = file.parentFile?.list()?.contains(name) ?: throw IOException("Cannot inspect sets directory")
        if (entry != file || (present && !entry.isFile)) {
            throw IOException("Set destination must be a regular non-symlink file")
        }
        return file
    }

    fun write(destination: File, replaceExisting: Boolean, prepare: (File) -> Unit) {
        synchronized(lock) {
            if (destination.exists() && !replaceExisting) throw SetAlreadyExistsException(destination.name)
            val staged = File.createTempFile(".set-", ".tmp", destination.parentFile)
            try {
                prepare(staged)
                FileOutputStream(staged, true).use { it.fd.sync() }
                // Android's same-filesystem rename replaces atomically; directory fsync is not provided.
                if (!staged.renameTo(destination)) throw IOException("Cannot commit set archive")
            } finally {
                staged.delete()
            }
        }
    }
}
