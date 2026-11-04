package su.linka.pictures

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Durable, backup-excluded editor ownership. Only the opaque token belongs in saved state. */
class EditorDraftStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "editor-drafts")
    private val archives = File(context.filesDir, "sets")

    data class Draft(val token: String, val set: Set, val state: JSONObject)

    fun create(source: Set): Draft {
        val token = UUID.randomUUID().toString()
        val board = File(directory(token), "board")
        if (!board.mkdirs()) throw IOException("Cannot create draft workspace")
        source.getFolder().walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(source.getFolder()).path
            val target = File(board, relative)
            require(file.canonicalPath.startsWith(source.getFolder().canonicalPath + File.separator))
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
        }
        val manifest = SetManifest(File(board, "config.json"), source.getManifest().toDocument(),
            source.getManifest().archiveFile)
        return Draft(token, Set(manifest, board), JSONObject())
    }

    fun capture(token: String, set: Set, state: JSONObject) {
        require(owns(token, set.getFolder()))
        val snapshot = JSONObject().put("version", 1)
            .put("model", EditorDraftJson.board(set.getManifest())).put("state", state)
            .put("archive", set.getManifest().archiveFile?.name ?: JSONObject.NULL)
        write(File(directory(token), "snapshot.json")) { it.write(snapshot.toString().toByteArray()) }
    }

    fun restore(token: String?): Draft? {
        if (token == null || !TOKEN.matches(token)) return null
        return try {
            val folder = directory(token)
            val snapshot = JSONObject(AtomicFile(File(folder, "snapshot.json")).readFully().toString(Charsets.UTF_8))
            if (snapshot.getInt("version") != 1) return null
            val board = File(folder, "board")
            if (!board.isDirectory || board.canonicalFile.parentFile != folder.canonicalFile) return null
            val model = snapshot.getJSONObject("model")
            val manifest = SetManifest(File(board, "config.json"), model)
            val archive = snapshot.optString("archive")
            if (archive.isNotEmpty() && archive != "null") {
                if (File(archive).name != archive) return null
                manifest.archiveFile = File(archives, archive)
            }
            if (manifest.cards.any { card ->
                    listOfNotNull(card.imagePath, card.audioPath).any { path ->
                        !File(board, path).canonicalPath.startsWith(board.canonicalPath + File.separator)
                    }
                }) return null
            Draft(token, Set(manifest, board), snapshot.getJSONObject("state"))
        } catch (_: Exception) {
            null
        }
    }

    fun mediaDirectory(token: String): File = File(directory(token), "media").also {
        require(it.canonicalFile.parentFile == directory(token).canonicalFile)
        if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create draft media directory")
    }

    fun owns(token: String, workspace: File): Boolean =
        TOKEN.matches(token) && workspace.canonicalFile.parentFile == directory(token).canonicalFile &&
            workspace.canonicalFile == File(directory(token), "board").canonicalFile

    /** Explicit save/cancel only; never use from onDestroy or to prune another owner's workspace. */
    fun discard(token: String) {
        directory(token).deleteRecursively()
    }

    private fun directory(token: String): File {
        require(TOKEN.matches(token)) { "Invalid draft token" }
        val folder = File(root, token)
        require(folder.canonicalFile.parentFile == root.canonicalFile) { "Draft outside owned root" }
        return folder
    }

    companion object {
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        internal fun write(file: File, writeContent: (FileOutputStream) -> Unit) {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                writeContent(output)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        }
    }
}
