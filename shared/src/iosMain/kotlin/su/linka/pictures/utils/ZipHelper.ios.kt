package su.linka.pictures.utils

import platform.Foundation.*
import kotlinx.cinterop.*
import platform.posix.*

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual class ZipHelper {
    
    actual fun extractAll(zipPath: String, destinationPath: String) {
        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtPath(
            destinationPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        
        val command = "/usr/bin/unzip -o -q '$zipPath' -d '$destinationPath'"
        val exitCode = executeShellCommand(command)
        
        if (exitCode != 0) {
            throw Exception("Failed to extract zip file: $zipPath (exit code: $exitCode)")
        }
    }
    
    actual fun extractFile(zipPath: String, fileInZip: String, destinationPath: String) {
        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtPath(
            destinationPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        
        val command = "/usr/bin/unzip -o -q '$zipPath' '$fileInZip' -d '$destinationPath'"
        val exitCode = executeShellCommand(command)
        
        if (exitCode != 0) {
            throw Exception("Failed to extract file $fileInZip from zip: $zipPath (exit code: $exitCode)")
        }
    }
    
    actual fun createZip(sourcePath: String, zipPath: String) {
        val fileManager = NSFileManager.defaultManager
        
        val parentDir = (zipPath as NSString).stringByDeletingLastPathComponent
        fileManager.createDirectoryAtPath(
            parentDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        
        if (fileManager.fileExistsAtPath(zipPath)) {
            fileManager.removeItemAtPath(zipPath, null)
        }
        
        val command = "cd '$sourcePath' && /usr/bin/zip -r -q '$zipPath' ."
        val exitCode = executeShellCommand(command)
        
        if (exitCode != 0) {
            throw Exception("Failed to create zip file: $zipPath (exit code: $exitCode)")
        }
    }
    
    actual fun listFiles(zipPath: String): List<String> {
        val tempDir = NSTemporaryDirectory() + "temp_list_${NSUUID().UUIDString}"
        extractAll(zipPath, tempDir)
        
        val fileManager = NSFileManager.defaultManager
        val enumerator = fileManager.enumeratorAtPath(tempDir)
        val files = mutableListOf<String>()
        
        while (true) {
            val item = enumerator?.nextObject() ?: break
            if (item is String) {
                files.add(item)
            }
        }
        
        fileManager.removeItemAtPath(tempDir, null)
        return files
    }
    
    @OptIn(ExperimentalForeignApi::class)
    private fun executeShellCommand(command: String): Int {
        memScoped {
            val pipe = popen(command, "r")
            if (pipe == null) {
                return -1
            }
            
            val buffer = allocArray<ByteVar>(4096)
            while (fgets(buffer, 4096, pipe) != null) {
                // Read output (for debugging if needed)
            }
            
            val status = pclose(pipe)
            return (status shr 8) and 0xFF
        }
    }
}
