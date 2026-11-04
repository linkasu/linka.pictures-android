package su.linka.pictures

import java.io.File
import java.io.IOException

@Throws(IOException::class)
fun resolveImportedSetFile(directory: File, fileName: String?): File {
    if (fileName.isNullOrEmpty() || fileName == "." || fileName == ".." ||
        fileName.any { it == '/' || it == '\\' || it == '\u0000' }
    ) {
        throw IOException("Invalid import file name")
    }
    val canonicalDirectory = directory.canonicalFile
    val destination = File(canonicalDirectory, fileName).canonicalFile
    if (destination.parentFile != canonicalDirectory) {
        throw IOException("Import destination is outside sets directory")
    }
    return destination
}
