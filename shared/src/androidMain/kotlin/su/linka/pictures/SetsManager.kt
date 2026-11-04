package su.linka.pictures

import android.content.Context
import android.content.res.AssetManager
import net.lingala.zip4j.exception.ZipException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayList
import java.util.UUID

class SetsManager(private val context: Context) {

    private val cookie = Cookie(context)

    @Throws(IOException::class)
    fun loadDefaultSets() {
        if (cookie.get(Cookie.ASSETS_LOADER, false)) {
            return
        }
        val sets = getSetsDirectory()
        val assetManager = getAssetsManager()
        val list = assetManager.list("sets") ?: emptyArray()
        for (name in list) {
            assetManager.open("sets/$name").use { input ->
                val outFile = File(sets, name)
                FileOutputStream(outFile).use { output ->
                    copyStream(input, output)
                }
            }
        }
        cookie.set(Cookie.ASSETS_LOADER, true)
    }

    fun getSetsDirectory(): File {
        val sets = File(getRootDirectory(), "sets")
        if (!sets.exists()) {
            sets.mkdirs()
        }
        return sets
    }

    private fun getRootDirectory(): File = context.filesDir

    private fun getAssetsManager(): AssetManager = context.assets

    fun getSets(): Array<SetManifest> {
        val files = getSetsDirectory().listFiles() ?: return emptyArray()
        files.sortWith { first, second ->
            when {
                first.lastModified() > second.lastModified() -> -1
                first.lastModified() < second.lastModified() -> 1
                else -> 0
            }
        }
        val manifests = ArrayList<SetManifest>(files.size)
        for (file in files) {
            try {
                if (file.isFile) {
                    manifests.add(getSetManifest(file))
                }
            } catch (error: IOException) {
                error.printStackTrace()
            }
        }
        return manifests.toTypedArray()
    }

    @Throws(IOException::class)
    fun getSetManifest(name: String): SetManifest = getSetManifest(getSetFile(name))

    @Throws(IOException::class)
    fun getSetManifest(file: File): SetManifest {
        return try {
            val configFile = extractManifestConfig(file)
            parseManifest(file, configFile)
        } catch (error: JSONException) {
            throw IOException(error)
        } catch (error: ZipException) {
            throw IOException(error)
        }
    }

    @Throws(JSONException::class, IOException::class)
    private fun parseManifest(archiveFile: File?, configFile: File): SetManifest {
        val raw = readStringFile(configFile)
        return SetManifest(configFile, JSONObject(raw), archiveFile)
    }

    private fun createWorkspace(): File {
        val root = getWorkspaceRoot()
        val folder = File(root, UUID.randomUUID().toString())
        if (folder.exists()) {
            folder.deleteRecursively()
        }
        folder.mkdirs()
        return folder
    }

    @Throws(IOException::class)
    private fun readStringFile(file: File): String {
        val output = java.io.ByteArrayOutputStream()
        file.inputStream().use { input ->
            BoundedStreams.copy(input, output, ArchiveLimits().maxConfigBytes)
        }
        return output.toString("UTF-8")
    }

    @Throws(ZipException::class)
    fun getSet(name: String): Set = getSet(getSetFile(name))

    @Throws(ZipException::class)
    private fun getSet(setFile: File): Set {
        val outputDir = createWorkspace()
        val manifest = try {
            ArchiveReader().extract(setFile, outputDir)
            parseManifest(setFile, File(outputDir, CONFIG_FILE_NAME))
        } catch (error: JSONException) {
            outputDir.deleteRecursively()
            throw ZipException(error)
        } catch (error: IOException) {
            outputDir.deleteRecursively()
            throw ZipException(error)
        }
        return Set(manifest, outputDir)
    }

    fun getSetFile(name: String): File = File(getSetsDirectory(), name)

    fun createSet(): Set {
        val folder = createWorkspace()
        folder.mkdirs()
        val file = File(folder, CONFIG_FILE_NAME)
        val manifest = SetManifest(file)
        return Set(manifest, folder)
    }

    fun save(set: Set, setName: String, callback: Callback<Any?>) {
        try {
            set.writeConfig()
            val destination = File(getSetsDirectory(), setName)
            Utils.zipFolder(set.getFolder().absolutePath, destination.absolutePath)
            set.getManifest().archiveFile = destination
            clearPreview(set.getManifest().cacheKey)
            callback.onDone(null)
        } catch (error: IOException) {
            error.printStackTrace()
            callback.onFail(error)
        }
    }

    fun delete(manifest: SetManifest) {
        manifest.archiveFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        clearPreview(manifest.cacheKey)
    }

    fun rename(manifest: SetManifest, result: String) {
        val archive = manifest.archiveFile ?: return
        val cacheKey = manifest.cacheKey
        val parent = archive.parentFile ?: return
        val target = File(parent, "$result.linka")
        if (archive.renameTo(target)) {
            clearPreview(cacheKey)
            manifest.archiveFile = target
        }
    }

    @Throws(ZipException::class)
    fun getSetImage(manifest: SetManifest, path: String?): File? {
        if (path == null) return null
        val archive = manifest.archiveFile ?: return null
        try {
            su.linka.pictures.shared.BoardLimits.validateMediaPath(path)
        } catch (error: IllegalArgumentException) {
            throw ZipException(error)
        }
        val previewDir = ensurePreviewDir(archive, manifest.cacheKey)
        val image = File(previewDir, path)
        if (!image.exists()) {
            try {
                ArchiveReader().extract(archive, previewDir, path)
            } catch (error: IOException) {
                throw ZipException(error)
            }
        }
        return image
    }

    @Throws(ZipException::class)
    private fun extractManifestConfig(file: File): File {
        val previewDir = ensurePreviewDir(file)
        val config = File(previewDir, CONFIG_FILE_NAME)
        try {
            ArchiveReader().extract(file, previewDir, CONFIG_FILE_NAME)
        } catch (error: IOException) {
            throw ZipException(error)
        }
        return config
    }

    private fun ensurePreviewDir(archive: File, cacheKey: String = archive.nameWithoutExtension): File {
        val dir = File(getPreviewRoot(), cacheKey)
        val marker = File(dir, PREVIEW_MARKER)
        val stamp = archive.lastModified().toString()
        val currentStamp = if (marker.exists()) runCatching { marker.readText() }.getOrNull() else null
        if (currentStamp != stamp) {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            dir.mkdirs()
            marker.writeText(stamp)
        } else if (!dir.exists()) {
            dir.mkdirs()
            marker.writeText(stamp)
        }
        return dir
    }

    private fun clearPreview(cacheKey: String) {
        val dir = File(getPreviewRoot(), cacheKey)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun getPreviewRoot(): File {
        val root = File(context.cacheDir, "previews")
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    private fun getWorkspaceRoot(): File {
        val root = File(context.cacheDir, "workspace")
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
    }

    companion object {
        private const val TAG = "SetsManager"
        private const val CONFIG_FILE_NAME = "config.json"
        private const val PREVIEW_MARKER = ".source"
    }
}
