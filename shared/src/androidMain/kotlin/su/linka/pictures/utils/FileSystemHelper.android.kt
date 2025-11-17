package su.linka.pictures.utils

import android.content.Context
import java.io.File

actual class FileSystemHelper(private val context: Context) {
    
    actual fun getSetsDirectory(): String {
        val sets = File(context.filesDir, "sets")
        if (!sets.exists()) {
            sets.mkdirs()
        }
        return sets.absolutePath
    }
    
    actual fun getWorkspaceDirectory(workspaceId: String): String {
        val workspace = File(context.cacheDir, "workspace/$workspaceId")
        if (!workspace.exists()) {
            workspace.mkdirs()
        }
        return workspace.absolutePath
    }
    
    actual fun getCacheDirectory(): String {
        val previews = File(context.cacheDir, "previews")
        if (!previews.exists()) {
            previews.mkdirs()
        }
        return previews.absolutePath
    }
    
    actual fun fileExists(path: String): Boolean {
        return File(path).exists()
    }
    
    actual fun deleteFile(path: String): Boolean {
        return File(path).delete()
    }
    
    actual fun deleteDirectory(path: String): Boolean {
        return File(path).deleteRecursively()
    }
    
    actual fun createDirectory(path: String): Boolean {
        return File(path).mkdirs()
    }
    
    actual fun listFiles(directory: String): List<String> {
        return File(directory).listFiles()?.map { it.absolutePath } ?: emptyList()
    }
    
    actual fun readBytes(path: String): ByteArray {
        return File(path).readBytes()
    }
    
    actual fun writeBytes(path: String, data: ByteArray) {
        File(path).writeBytes(data)
    }
    
    actual fun readText(path: String): String {
        return File(path).readText()
    }
    
    actual fun writeText(path: String, text: String) {
        File(path).parentFile?.mkdirs()
        File(path).writeText(text)
    }
    
    actual fun copyFile(source: String, destination: String) {
        File(source).copyTo(File(destination), overwrite = true)
    }
    
    actual fun getFileName(path: String): String {
        return File(path).name
    }
    
    actual fun getFileNameWithoutExtension(path: String): String {
        return File(path).nameWithoutExtension
    }
    
    actual fun getFileExtension(path: String): String {
        return File(path).extension
    }
    
    actual fun joinPath(vararg components: String): String {
        return components.joinToString(File.separator)
    }
    
    actual fun getLastModified(path: String): Long {
        return File(path).lastModified()
    }
}

