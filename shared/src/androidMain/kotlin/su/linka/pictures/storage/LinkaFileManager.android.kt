package su.linka.pictures.storage

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import su.linka.pictures.Cookie
import su.linka.pictures.model.SetManifest
import su.linka.pictures.utils.FileSystemHelper
import su.linka.pictures.utils.ZipHelper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

actual class LinkaFileManager(private val context: Context) {
    
    private val fileSystem = FileSystemHelper(context)
    private val zipHelper = ZipHelper()
    private val cookie = Cookie(context)
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    actual fun loadDefaultSets() {
        if (cookie.get(Cookie.ASSETS_LOADER, false)) {
            return
        }
        
        val setsDir = fileSystem.getSetsDirectory()
        val assetManager = context.assets
        val list = assetManager.list("sets") ?: emptyArray()
        
        for (name in list) {
            assetManager.open("sets/$name").use { input ->
                val outFile = File(setsDir, name)
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        cookie.set(Cookie.ASSETS_LOADER, true)
    }
    
    actual fun getSets(): List<SetManifest> {
        val setsDir = fileSystem.getSetsDirectory()
        val files = File(setsDir).listFiles() ?: return emptyList()
        
        files.sortWith { first, second ->
            when {
                first.lastModified() > second.lastModified() -> -1
                first.lastModified() < second.lastModified() -> 1
                else -> 0
            }
        }
        
        val manifests = mutableListOf<SetManifest>()
        for (file in files) {
            if (file.isFile) {
                try {
                    manifests.add(getSetManifest(file))
                } catch (error: Exception) {
                    error.printStackTrace()
                }
            }
        }
        
        return manifests
    }
    
    actual fun getSet(fileName: String): LinkaSet {
        val setsDir = fileSystem.getSetsDirectory()
        val setFile = File(setsDir, fileName)
        
        val workspaceId = UUID.randomUUID().toString()
        val workspaceDir = fileSystem.getWorkspaceDirectory(workspaceId)
        
        zipHelper.extractAll(setFile.absolutePath, workspaceDir)
        
        val configPath = fileSystem.joinPath(workspaceDir, "config.json")
        val manifest = parseManifest(configPath, fileName)
        
        return LinkaSet(manifest, workspaceId)
    }
    
    actual fun createSet(): LinkaSet {
        val workspaceId = UUID.randomUUID().toString()
        val workspaceDir = fileSystem.getWorkspaceDirectory(workspaceId)
        fileSystem.createDirectory(workspaceDir)
        
        val manifest = SetManifest()
        
        return LinkaSet(manifest, workspaceId)
    }
    
    actual fun saveSet(set: LinkaSet, fileName: String): Result<Unit> {
        return try {
            val workspaceDir = fileSystem.getWorkspaceDirectory(set.workspaceId)
            val configPath = fileSystem.joinPath(workspaceDir, "config.json")
            
            val jsonString = json.encodeToString(set.manifest)
            fileSystem.writeText(configPath, jsonString)
            
            val setsDir = fileSystem.getSetsDirectory()
            val destinationPath = fileSystem.joinPath(setsDir, fileName)
            
            zipHelper.createZip(workspaceDir, destinationPath)
            
            set.manifest.archiveFileName = fileName
            
            clearPreview(set.manifest.cacheKey)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    actual fun deleteSet(manifest: SetManifest): Result<Unit> {
        return try {
            val setsDir = fileSystem.getSetsDirectory()
            manifest.archiveFileName?.let { fileName ->
                val filePath = fileSystem.joinPath(setsDir, fileName)
                fileSystem.deleteFile(filePath)
            }
            clearPreview(manifest.cacheKey)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    actual fun renameSet(manifest: SetManifest, newName: String): Result<Unit> {
        return try {
            val setsDir = fileSystem.getSetsDirectory()
            val oldFileName = manifest.archiveFileName ?: return Result.failure(Exception("No archive file"))
            val oldPath = fileSystem.joinPath(setsDir, oldFileName)
            val newPath = fileSystem.joinPath(setsDir, "$newName.linka")
            
            val oldFile = File(oldPath)
            val newFile = File(newPath)
            
            if (oldFile.renameTo(newFile)) {
                clearPreview(manifest.cacheKey)
                manifest.archiveFileName = "$newName.linka"
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to rename file"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    actual fun getImagePath(manifest: SetManifest, imagePath: String?): String? {
        if (imagePath == null) return null
        
        val setsDir = fileSystem.getSetsDirectory()
        val archiveFileName = manifest.archiveFileName ?: return null
        val archivePath = fileSystem.joinPath(setsDir, archiveFileName)
        
        val previewDir = ensurePreviewDir(archivePath, manifest.cacheKey)
        val imageFullPath = fileSystem.joinPath(previewDir, imagePath)
        
        if (!fileSystem.fileExists(imageFullPath)) {
            val imageDir = File(imageFullPath).parentFile?.absolutePath
            if (imageDir != null) {
                fileSystem.createDirectory(imageDir)
            }
            zipHelper.extractFile(archivePath, imagePath, previewDir)
        }
        
        return imageFullPath
    }
    
    actual fun saveImage(workspaceId: String, imageData: ByteArray, format: ImageFormat): String {
        val workspaceDir = fileSystem.getWorkspaceDirectory(workspaceId)
        val extension = when (format) {
            ImageFormat.PNG -> "png"
            ImageFormat.JPEG -> "jpg"
            ImageFormat.WEBP -> "webp"
        }
        val fileName = "${UUID.randomUUID()}.$extension"
        val filePath = fileSystem.joinPath(workspaceDir, fileName)
        
        fileSystem.writeBytes(filePath, imageData)
        
        return fileName
    }
    
    actual fun saveAudio(workspaceId: String, audioData: ByteArray, fileName: String): String {
        val workspaceDir = fileSystem.getWorkspaceDirectory(workspaceId)
        val filePath = fileSystem.joinPath(workspaceDir, fileName)
        
        fileSystem.writeBytes(filePath, audioData)
        
        return fileName
    }
    
    actual fun copyAudioFile(workspaceId: String, sourcePath: String): String {
        val workspaceDir = fileSystem.getWorkspaceDirectory(workspaceId)
        val sourceFile = File(sourcePath)
        val fileName = sourceFile.name
        val destPath = fileSystem.joinPath(workspaceDir, fileName)
        
        val destFile = File(destPath)
        if (destFile.exists() && destFile.absolutePath != sourceFile.absolutePath) {
            val uniqueName = generateUniqueName(workspaceDir, fileName)
            val uniquePath = fileSystem.joinPath(workspaceDir, uniqueName)
            fileSystem.copyFile(sourcePath, uniquePath)
            return uniqueName
        } else if (destFile.absolutePath == sourceFile.absolutePath) {
            return fileName
        } else {
            fileSystem.copyFile(sourcePath, destPath)
            return fileName
        }
    }
    
    actual fun getAudioPath(workspaceId: String, audioPath: String?): String? {
        if (audioPath == null) return null
        val workspaceDir = fileSystem.getWorkspaceDirectory(workspaceId)
        return fileSystem.joinPath(workspaceDir, audioPath)
    }
    
    private fun getSetManifest(file: File): SetManifest {
        val configFile = extractManifestConfig(file)
        return parseManifest(configFile.absolutePath, file.name)
    }
    
    private fun parseManifest(configPath: String, archiveFileName: String? = null): SetManifest {
        val jsonString = fileSystem.readText(configPath)
        val manifest = json.decodeFromString<SetManifest>(jsonString)
        manifest.archiveFileName = archiveFileName
        return manifest
    }
    
    private fun extractManifestConfig(file: File): File {
        val previewDir = ensurePreviewDir(file.absolutePath)
        val config = File(previewDir, "config.json")
        if (config.exists()) {
            config.delete()
        }
        zipHelper.extractFile(file.absolutePath, "config.json", previewDir)
        return config
    }
    
    private fun ensurePreviewDir(archivePath: String, cacheKey: String = fileSystem.getFileNameWithoutExtension(archivePath)): String {
        val cacheDir = fileSystem.getCacheDirectory()
        val previewDir = fileSystem.joinPath(cacheDir, cacheKey)
        val markerPath = fileSystem.joinPath(previewDir, ".source")
        
        val stamp = fileSystem.getLastModified(archivePath).toString()
        val currentStamp = if (fileSystem.fileExists(markerPath)) {
            try {
                fileSystem.readText(markerPath)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        if (currentStamp != stamp) {
            if (fileSystem.fileExists(previewDir)) {
                fileSystem.deleteDirectory(previewDir)
            }
            fileSystem.createDirectory(previewDir)
            fileSystem.writeText(markerPath, stamp)
        } else if (!fileSystem.fileExists(previewDir)) {
            fileSystem.createDirectory(previewDir)
            fileSystem.writeText(markerPath, stamp)
        }
        
        return previewDir
    }
    
    private fun clearPreview(cacheKey: String) {
        val cacheDir = fileSystem.getCacheDirectory()
        val previewDir = fileSystem.joinPath(cacheDir, cacheKey)
        if (fileSystem.fileExists(previewDir)) {
            fileSystem.deleteDirectory(previewDir)
        }
    }
    
    private fun generateUniqueName(directory: String, originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex != -1) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex != -1) originalName.substring(dotIndex) else ""
        
        var index = 1
        while (true) {
            val candidateName = "$baseName-$index$extension"
            val candidatePath = fileSystem.joinPath(directory, candidateName)
            if (!fileSystem.fileExists(candidatePath)) {
                return candidateName
            }
            index++
        }
    }
}

