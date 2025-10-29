package su.linka.pictures

import android.content.Context
import android.content.res.AssetManager
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
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
        val text = StringBuilder()
        BufferedReader(FileReader(file)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                text.append(line)
                text.append('\n')
            }
        }
        return text.toString()
    }

    @Throws(ZipException::class)
    fun getSet(name: String): Set = getSet(getSetFile(name))

    @Throws(ZipException::class)
    private fun getSet(setFile: File): Set {
        val outputDir = createWorkspace()
        ZipFile(setFile).extractAll(outputDir.absolutePath)
        val manifest = try {
            parseManifest(setFile, File(outputDir, CONFIG_FILE_NAME))
        } catch (error: JSONException) {
            throw ZipException(error)
        } catch (error: IOException) {
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
        set.writeConfig()
        try {
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
        val previewDir = ensurePreviewDir(archive, manifest.cacheKey)
        val image = File(previewDir, path)
        if (!image.exists()) {
            image.parentFile?.mkdirs()
            ZipFile(archive).extractFile(path, previewDir.absolutePath)
        }
        return image
    }

    @Throws(ZipException::class)
    private fun extractManifestConfig(file: File): File {
        val previewDir = ensurePreviewDir(file)
        val config = File(previewDir, CONFIG_FILE_NAME)
        if (config.exists()) {
            config.delete()
        }
        ZipFile(file).extractFile(CONFIG_FILE_NAME, previewDir.absolutePath)
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
