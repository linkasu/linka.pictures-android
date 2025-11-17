package su.linka.pictures.utils

import net.lingala.zip4j.ZipFile
import java.io.File

actual class ZipHelper {
    
    actual fun extractAll(zipPath: String, destinationPath: String) {
        ZipFile(zipPath).extractAll(destinationPath)
    }
    
    actual fun extractFile(zipPath: String, fileInZip: String, destinationPath: String) {
        ZipFile(zipPath).extractFile(fileInZip, destinationPath)
    }
    
    actual fun createZip(sourcePath: String, zipPath: String) {
        val zipFile = ZipFile(zipPath)
        val sourceDir = File(sourcePath)
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                zipFile.addFile(file)
            }
        }
    }
    
    actual fun listFiles(zipPath: String): List<String> {
        return ZipFile(zipPath).fileHeaders.map { it.fileName }
    }
}

