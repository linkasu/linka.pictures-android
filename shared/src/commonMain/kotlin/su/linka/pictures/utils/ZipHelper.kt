package su.linka.pictures.utils

expect class ZipHelper {
    fun extractAll(zipPath: String, destinationPath: String)
    
    fun extractFile(zipPath: String, fileInZip: String, destinationPath: String)
    
    fun createZip(sourcePath: String, zipPath: String)
    
    fun listFiles(zipPath: String): List<String>
}

