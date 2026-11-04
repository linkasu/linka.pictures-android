package su.linka.pictures

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.util.FileUtils
import su.linka.pictures.shared.BoardLimits
import java.io.File
import java.io.IOException

class ArchiveReader(private val limits: ArchiveLimits = ArchiveLimits()) {
    @Throws(IOException::class)
    fun extract(archive: File, directory: File, path: String? = null) {
        if (FileUtils.isNumberedSplitFile(archive)) throw IOException("Numbered split archives are not supported")
        if (archive.length() > limits.maxInputBytes) throw IOException("Archive exceeds input limit")
        ZipDirectoryBounds.validate(archive, limits)
        ZipFile(archive).use { zip ->
            val headers = zip.fileHeaders
            validate(headers)
            val selected = if (path == null) headers else listOf(
                headers.firstOrNull { it.fileName == path && !it.isDirectory }
                    ?: throw IOException("Missing archive entry: $path")
            )
            // Check every target before opening any output, including existing filesystem symlinks.
            headers.forEach { target(directory, it) }
            var total = 0L
            for (header in selected) {
                val output = target(directory, header)
                if (header.isDirectory) {
                    if (!output.isDirectory && !output.mkdirs()) throw IOException("Cannot create directory")
                    continue
                }
                val parent = output.parentFile ?: throw IOException("Missing parent directory")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create directory")
                zip.getInputStream(header).use { input ->
                    output.outputStream().use { stream ->
                        total += BoundedStreams.copy(input, stream, minOf(entryLimit(header), limits.maxTotalBytes - total))
                    }
                }
            }
        }
    }

    private fun validate(headers: List<FileHeader>) {
        if (headers.size > limits.maxEntries) throw IOException("Too many archive entries")
        val names = HashSet<String>()
        var total = 0L
        for (header in headers) {
            val name = header.fileName.removeSuffix(if (header.isDirectory) "/" else "")
            try {
                BoardLimits.validateMediaPath(name)
            } catch (error: IllegalArgumentException) {
                throw IOException(error.message, error)
            }
            if (!names.add(name)) throw IOException("Duplicate archive path: $name")
            val attributes = header.externalFileAttributes
            val mode = if (attributes != null && attributes.size >= 4)
                (attributes[2].toInt() and 255) or ((attributes[3].toInt() and 255) shl 8) else 0
            if (mode and 0xF000 == 0xA000) throw IOException("Symlinks are not supported")
            if (header.isEncrypted) throw IOException("Encrypted archives are not supported")
            val size = header.uncompressedSize
            if (size < 0 || header.compressedSize < 0 || size > entryLimit(header) || size > limits.maxTotalBytes - total)
                throw IOException("Archive exceeds uncompressed limit")
            total += size
        }
        val files = headers.filterNot { it.isDirectory }.map { it.fileName }.toHashSet()
        for (name in names) {
            var slash = name.indexOf('/')
            while (slash >= 0) {
                if (name.substring(0, slash) in files) throw IOException("Conflicting archive paths")
                slash = name.indexOf('/', slash + 1)
            }
        }
    }

    private fun entryLimit(header: FileHeader): Long =
        if (header.fileName == "config.json") limits.maxConfigBytes else limits.maxMediaBytes

    private fun target(directory: File, header: FileHeader): File {
        val root = directory.canonicalFile
        val file = File(root, header.fileName.removeSuffix("/"))
        if (file.canonicalPath != file.absolutePath || !file.canonicalPath.startsWith(root.path + File.separator))
            throw IOException("Archive path escapes destination or uses a symlink")
        return file
    }
}
