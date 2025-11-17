package su.linka.pictures.utils

import platform.Foundation.*
import kotlinx.cinterop.*
import platform.posix.memcpy

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual class FileSystemHelper {
    
    private val fileManager = NSFileManager.defaultManager
    
    actual fun getSetsDirectory(): String {
        val documentsDir = getDocumentsDirectory()
        val setsDir = "$documentsDir/sets"
        createDirectory(setsDir)
        return setsDir
    }
    
    actual fun getWorkspaceDirectory(workspaceId: String): String {
        val cachesDir = getCacheDirectory()
        val workspaceDir = "$cachesDir/workspace/$workspaceId"
        createDirectory(workspaceDir)
        return workspaceDir
    }
    
    actual fun getCacheDirectory(): String {
        val cachesDirs = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory,
            NSUserDomainMask,
            true
        )
        val cachesDir = (cachesDirs as List<*>).firstOrNull() as? String ?: ""
        val previewsDir = "$cachesDir/previews"
        createDirectory(previewsDir)
        return previewsDir
    }
    
    actual fun fileExists(path: String): Boolean {
        return fileManager.fileExistsAtPath(path)
    }
    
    actual fun deleteFile(path: String): Boolean {
        return try {
            fileManager.removeItemAtPath(path, null)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    actual fun deleteDirectory(path: String): Boolean {
        return try {
            fileManager.removeItemAtPath(path, null)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    actual fun createDirectory(path: String): Boolean {
        if (fileExists(path)) return true
        return try {
            fileManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
            true
        } catch (e: Exception) {
            false
        }
    }
    
    actual fun listFiles(directory: String): List<String> {
        val contents = fileManager.contentsOfDirectoryAtPath(directory, null) as? List<*>
        return contents?.mapNotNull { fileName ->
            if (fileName is String) {
                "$directory/$fileName"
            } else null
        } ?: emptyList()
    }
    
    actual fun readBytes(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path)
            ?: throw Exception("Failed to read file: $path")
        return data.toByteArray()
    }
    
    actual fun writeBytes(path: String, data: ByteArray) {
        val nsData = data.toNSData()
        nsData.writeToFile(path, atomically = true)
    }
    
    actual fun readText(path: String): String {
        return NSString.stringWithContentsOfFile(
            path,
            encoding = NSUTF8StringEncoding,
            error = null
        ) as String? ?: throw Exception("Failed to read file: $path")
    }
    
    actual fun writeText(path: String, text: String) {
        val dir = (path as NSString).stringByDeletingLastPathComponent
        createDirectory(dir)
        (text as NSString).writeToFile(
            path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )
    }
    
    actual fun copyFile(source: String, destination: String) {
        fileManager.copyItemAtPath(source, toPath = destination, error = null)
    }
    
    actual fun getFileName(path: String): String {
        return (path as NSString).lastPathComponent
    }
    
    actual fun getFileNameWithoutExtension(path: String): String {
        val fileName = (path as NSString).lastPathComponent
        return (fileName as NSString).stringByDeletingPathExtension
    }
    
    actual fun getFileExtension(path: String): String {
        return (path as NSString).pathExtension
    }
    
    actual fun joinPath(vararg components: String): String {
        return components.joinToString("/")
    }
    
    actual fun getLastModified(path: String): Long {
        val attributes = fileManager.attributesOfItemAtPath(path, null)
        val modificationDate = attributes?.get(NSFileModificationDate) as? NSDate
        return modificationDate?.timeIntervalSince1970?.toLong()?.times(1000) ?: 0L
    }
    
    private fun getDocumentsDirectory(): String {
        val documentsDirs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        )
        return (documentsDirs as List<*>).firstOrNull() as? String ?: ""
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    return ByteArray(length.toInt()).apply {
        usePinned {
            memcpy(it.addressOf(0), bytes, length)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    return usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }
}

