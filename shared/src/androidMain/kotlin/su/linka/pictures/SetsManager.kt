package su.linka.pictures

import android.content.Context
import android.content.res.AssetManager
import net.lingala.zip4j.exception.ZipException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.UUID

class SetsManager(private val context: Context) {

    private val cookie = Cookie(context)

    @Throws(IOException::class)
    fun loadDefaultSets() = synchronized(AtomicSetFile.lock) {
        if (cookie.get(Cookie.ASSETS_LOADER, false)) {
            return@synchronized
        }
        val sets = getSetsDirectory()
        val assetManager = getAssetsManager()
        val list = assetManager.list("sets") ?: throw IOException("Cannot list bundled sets")
        for (name in list) {
            val destination = AtomicSetFile.resolve(sets, name)
            // A retry after an interrupted first launch must not replace an already edited set.
            if (destination.exists()) continue
            assetManager.open("sets/$name").use { input ->
                importSet(input, name)
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
                if (file.isFile && file.name.endsWith(".linka")) {
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
        if (!folder.mkdirs()) throw IOException("Cannot create workspace")
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
        val outputDir = try {
            createWorkspace()
        } catch (error: IOException) {
            throw ZipException(error)
        }
        try {
            ArchiveReader().extract(setFile, outputDir)
            val manifest = parseManifest(setFile, File(outputDir, CONFIG_FILE_NAME))
            validateMedia(manifest, outputDir)
            return Set(manifest, outputDir, ownsWorkspace = true)
        } catch (error: Exception) {
            outputDir.deleteRecursively()
            throw ZipException(error)
        }
    }

    fun getSetFile(name: String): File = File(getSetsDirectory(), name)

    fun createSet(): Set {
        val folder = createWorkspace()
        try {
            val file = File(folder, CONFIG_FILE_NAME)
            val manifest = SetManifest(file)
            return Set(manifest, folder, ownsWorkspace = true)
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    fun save(set: Set, setName: String, callback: Callback<Any?>) {
        save(set, setName, false, callback)
    }

    fun save(set: Set, setName: String, replaceExisting: Boolean, callback: Callback<Any?>) {
        try {
            synchronized(AtomicSetFile.lock) {
                val destination = AtomicSetFile.resolve(getSetsDirectory(), setName)
                val manifest = set.getManifest()
                val owned = manifest.archiveFile?.canonicalFile == destination
                AtomicSetFile.write(destination, replaceExisting || owned) { staged ->
                    set.writeConfig()
                    Utils.zipFolder(set.getFolder().absolutePath, staged.absolutePath, set.referencedPaths())
                    validateArchive(staged)
                }
                val previousKey = manifest.cacheKey
                manifest.archiveFile = destination
                clearPreview(previousKey)
                clearPreview(manifest.cacheKey)
            }
        } catch (error: Exception) {
            if (error !is IOException && error !is IllegalArgumentException) throw error
            error.printStackTrace()
            callback.onFail(if (error is IOException) error else IOException("Invalid set config", error))
            return
        }
        callback.onDone(null)
    }

    @Throws(IOException::class)
    fun importSet(input: InputStream, name: String, replaceExisting: Boolean = false): File =
        synchronized(AtomicSetFile.lock) {
            val destination = AtomicSetFile.resolve(getSetsDirectory(), name)
            AtomicSetFile.write(destination, replaceExisting) { staged ->
                staged.outputStream().use { output ->
                    BoundedStreams.copy(input, output, ArchiveLimits().maxInputBytes)
                }
                validateArchive(staged)
            }
            clearPreview(destination.nameWithoutExtension)
            destination
        }

    private fun validateArchive(archive: File) {
        val workspace = createWorkspace()
        try {
            ArchiveReader().extract(archive, workspace)
            validateMedia(parseManifest(archive, File(workspace, CONFIG_FILE_NAME)), workspace)
        } catch (error: JSONException) {
            throw IOException(error)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun validateMedia(manifest: SetManifest, workspace: File) {
        for (card in manifest.cards) {
            for (path in listOfNotNull(card.imagePath, card.audioPath)) {
                if (path == CONFIG_FILE_NAME || !File(workspace, path).isFile) {
                    throw IOException("Missing or invalid card media: $path")
                }
            }
        }
    }

    /** Builds a complete snapshot without writing to the source workspace or library archive. */
    @Throws(IOException::class)
    fun prepareExport(set: Set): File {
        val root = File(context.cacheDir, "exports")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create export directory")
        val token = UUID.randomUUID().toString()
        val staging = File(root, "$token.work")
        val archive = File(root, "$token.linka")
        try {
            if (!staging.mkdir()) throw IOException("Cannot create export workspace")
            if (set.getManifest().cards.any { it.imagePath == CONFIG_FILE_NAME || it.audioPath == CONFIG_FILE_NAME }) {
                throw IOException("Config cannot be exported as card media")
            }
            File(staging, CONFIG_FILE_NAME).writeText(set.getManifest().toJSONObject().toString())
            val source = set.getFolder().canonicalFile
            val paths = set.referencedPaths()
            for (path in paths - CONFIG_FILE_NAME) {
                val media = File(source, path).canonicalFile
                val target = File(staging, path).canonicalFile
                if (!media.path.startsWith(source.path + File.separator) ||
                    !target.path.startsWith(staging.canonicalPath + File.separator) ||
                    target == File(staging, CONFIG_FILE_NAME).canonicalFile || !media.isFile) {
                    throw IOException("Invalid export media path")
                }
                target.parentFile?.mkdirs()
                media.copyTo(target)
            }
            Utils.zipFolder(staging.absolutePath, archive.absolutePath, paths)
            validateArchive(archive)
            return archive
        } catch (error: Exception) {
            archive.delete()
            throw if (error is IOException) error else IOException("Cannot prepare export snapshot", error)
        } finally {
            staging.deleteRecursively()
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

    fun rename(manifest: SetManifest, result: String, replaceExisting: Boolean = false) {
        synchronized(AtomicSetFile.lock) {
            val archive = manifest.archiveFile ?: return
            val cacheKey = manifest.cacheKey
            val target = AtomicSetFile.resolve(getSetsDirectory(), "$result.linka")
            if (archive.canonicalFile == target) return
            if (target.exists() && !replaceExisting) throw SetAlreadyExistsException(target.name)
            if (!archive.renameTo(target)) throw IOException("Cannot rename set archive")
            clearPreview(cacheKey)
            manifest.archiveFile = target
            clearPreview(manifest.cacheKey)
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

    companion object {
        private const val TAG = "SetsManager"
        private const val CONFIG_FILE_NAME = "config.json"
        private const val PREVIEW_MARKER = ".source"
    }
}
