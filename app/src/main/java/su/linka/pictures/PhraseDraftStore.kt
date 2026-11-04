package su.linka.pictures

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Full ordered card indices, never playback state; only the token belongs in a Bundle. */
class PhraseDraftStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "phrase-drafts")

    fun capture(token: String?, documentIdentity: String, indices: IntArray): String {
        val owner = token ?: UUID.randomUUID().toString()
        EditorDraftStore.write(file(owner)) { output ->
            val data = DataOutputStream(BufferedOutputStream(output))
            data.writeInt(1)
            data.writeUTF(documentIdentity)
            data.writeInt(indices.size)
            indices.forEach { data.writeInt(it) }
            data.flush()
        }
        return owner
    }

    fun restore(token: String?, documentIdentity: String): IntArray? {
        if (token == null || !TOKEN.matches(token)) return null
        return try {
            DataInputStream(ByteArrayInputStream(AtomicFile(file(token)).readFully())).use { data ->
                if (data.readInt() != 1 || data.readUTF() != documentIdentity) return null
                val count = data.readInt()
                if (count < 0 || count.toLong() * 4 != data.available().toLong()) return null
                IntArray(count) { data.readInt() }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun owns(token: String, candidate: File): Boolean =
        TOKEN.matches(token) && candidate.canonicalFile == file(token).canonicalFile

    /** Confirmed close only. Recreation and unrelated cleanup must leave active drafts alone. */
    fun discard(token: String) {
        AtomicFile(file(token)).delete()
    }

    private fun file(token: String): File {
        require(TOKEN.matches(token)) { "Invalid phrase draft token" }
        return File(root, "$token.bin").also {
            require(it.canonicalFile.parentFile == root.canonicalFile) { "Phrase outside owned root" }
        }
    }

    companion object {
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
