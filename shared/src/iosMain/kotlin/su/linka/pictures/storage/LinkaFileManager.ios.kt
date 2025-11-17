package su.linka.pictures.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*
import su.linka.pictures.model.SetManifest
import su.linka.pictures.utils.FileSystemHelper
import su.linka.pictures.utils.ZipHelper
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual class LinkaFileManager {
    
    private val fileSystem = FileSystemHelper()
    private val zipHelper = ZipHelper()
    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private companion object {
        const val ASSETS_LOADER_KEY = "ASSETS_LOADER_V2"
    }
    
    actual fun loadDefaultSets() {
        if (userDefaults.boolForKey(ASSETS_LOADER_KEY)) {
            return
        }
        
        val setsDir = fileSystem.getSetsDirectory()
        val bundle = NSBundle.mainBundle
        val resourcePath = bundle.resourcePath ?: return
        
        val bundleSetsPath = "$resourcePath/sets"
        println("LinkaFileManager: Checking bundleSetsPath: $bundleSetsPath")
        println("LinkaFileManager: Exists: ${fileSystem.fileExists(bundleSetsPath)}")
        
        if (!fileSystem.fileExists(bundleSetsPath)) {
            println("LinkaFileManager: sets directory not found in bundle")
            return
        }
        
        val files = fileSystem.listFiles(bundleSetsPath)
        println("LinkaFileManager: Found ${files.size} files in bundle")
        
        for (filePath in files) {
            val fileName = fileSystem.getFileName(filePath)
            if (fileName.endsWith(".linka")) {
                val destPath = fileSystem.joinPath(setsDir, fileName)
                println("LinkaFileManager: Copying $fileName to $destPath")
                fileSystem.copyFile(filePath, destPath)
            }
        }
        
        println("LinkaFileManager: Setting ASSETS_LOADER_KEY to true")
        userDefaults.setBool(true, forKey = ASSETS_LOADER_KEY)
    }
    
    actual fun getSets(): List<SetManifest> {
        val setsDir = fileSystem.getSetsDirectory()
        val files = fileSystem.listFiles(setsDir)
        
        data class FileWithTime(val path: String, val time: Long)
        
        val filesWithTime = files.map { path ->
            FileWithTime(path, fileSystem.getLastModified(path))
        }.sortedByDescending { it.time }
        
        val manifests = mutableListOf<SetManifest>()
        for (fileInfo in filesWithTime) {
            if (fileInfo.path.endsWith(".linka")) {
                try {
                    manifests.add(getSetManifest(fileInfo.path))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return manifests
    }
    
    actual fun getSet(fileName: String): LinkaSet {
        val setsDir = fileSystem.getSetsDirectory()
        val setPath = fileSystem.joinPath(setsDir, fileName)
        
        val workspaceId = NSUUID().UUIDString
        val workspaceDir = fileSystem.getWorkspaceDirectory(workspaceId)
        
        zipHelper.extractAll(setPath, workspaceDir)
        
        val configPath = fileSystem.joinPath(workspaceDir, "config.json")
        val manifest = parseManifest(configPath, fileName)
        
        return LinkaSet(manifest, workspaceId)
    }
    
    actual fun createSet(): LinkaSet {
        val workspaceId = NSUUID().UUIDString
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
            
            val fileManager = NSFileManager.defaultManager
            fileManager.moveItemAtPath(oldPath, toPath = newPath, error = null)
            
            clearPreview(manifest.cacheKey)
            manifest.archiveFileName = "$newName.linka"
            Result.success(Unit)
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
            val imageDir = (imageFullPath as NSString).stringByDeletingLastPathComponent
            fileSystem.createDirectory(imageDir)
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
        val fileName = "${NSUUID().UUIDString}.$extension"
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
        val fileName = fileSystem.getFileName(sourcePath)
        val destPath = fileSystem.joinPath(workspaceDir, fileName)
        
        if (fileSystem.fileExists(destPath) && destPath != sourcePath) {
            val uniqueName = generateUniqueName(workspaceDir, fileName)
            val uniquePath = fileSystem.joinPath(workspaceDir, uniqueName)
            fileSystem.copyFile(sourcePath, uniquePath)
            return uniqueName
        } else if (destPath == sourcePath) {
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
    
    private fun getSetManifest(filePath: String): SetManifest {
        val configFile = extractManifestConfig(filePath)
        val fileName = fileSystem.getFileName(filePath)
        return parseManifest(configFile, fileName)
    }
    
    private fun parseManifest(configPath: String, archiveFileName: String? = null): SetManifest {
        val jsonString = fileSystem.readText(configPath)
        val manifest = json.decodeFromString<SetManifest>(jsonString)
        manifest.archiveFileName = archiveFileName
        return manifest
    }
    
    private fun extractManifestConfig(filePath: String): String {
        val previewDir = ensurePreviewDir(filePath)
        val configPath = fileSystem.joinPath(previewDir, "config.json")
        if (fileSystem.fileExists(configPath)) {
            fileSystem.deleteFile(configPath)
        }
        zipHelper.extractFile(filePath, "config.json", previewDir)
        return configPath
    }
    
    private fun ensurePreviewDir(
        archivePath: String, 
        cacheKey: String = fileSystem.getFileNameWithoutExtension(archivePath)
    ): String {
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

