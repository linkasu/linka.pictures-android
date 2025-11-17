package su.linka.pictures.utils

expect class FileSystemHelper {
    fun getSetsDirectory(): String
    
    fun getWorkspaceDirectory(workspaceId: String): String
    
    fun getCacheDirectory(): String
    
    fun fileExists(path: String): Boolean
    
    fun deleteFile(path: String): Boolean
    
    fun deleteDirectory(path: String): Boolean
    
    fun createDirectory(path: String): Boolean
    
    fun listFiles(directory: String): List<String>
    
    fun readBytes(path: String): ByteArray
    
    fun writeBytes(path: String, data: ByteArray)
    
    fun readText(path: String): String
    
    fun writeText(path: String, text: String)
    
    fun copyFile(source: String, destination: String)
    
    fun getFileName(path: String): String
    
    fun getFileNameWithoutExtension(path: String): String
    
    fun getFileExtension(path: String): String
    
    fun joinPath(vararg components: String): String
    
    fun getLastModified(path: String): Long
}

