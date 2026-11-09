package su.linka.pictures.migration

import okio.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.storage.storageActive

internal class FileMigrationSource(private val root: Path, private val files: FileSystem) : MigrationSource {
    override val kind = MigrationSourceKind.LEGACY_FILES

    override fun validateTarget(target: Path) {
        require(root.isAbsolute && target.isAbsolute)
        val source = root.normalized().toString().trimEnd('/') + "/"
        val destination = target.normalized().toString().trimEnd('/') + "/"
        if (source.startsWith(destination) || destination.startsWith(source)) reject(ProblemCode.INVALID_DATA)
    }

    override suspend fun entries(): List<LegacyEntry> {
        val metadata = files.metadataOrNull(root) ?: return emptyList()
        if (!metadata.isDirectory || metadata.symlinkTarget != null || files.canonicalize(root) != root.normalized()) reject(ProblemCode.INVALID_DATA)
        val paths = files.list(root).sortedBy { it.name }
        if (paths.size > 100000) reject(ProblemCode.LIMIT_EXCEEDED)
        return paths.map { path ->
            storageActive()
            val before = checked(path)
            val size = before.size ?: reject(ProblemCode.INVALID_DATA)
            if (size > 2L * 1024 * 1024 * 1024) reject(ProblemCode.LIMIT_EXCEEDED)
            val hash = sourceHash(files.source(path), size)
            val after = checked(path)
            if (after.size != size || before.lastModifiedAtMillis != after.lastModifiedAtMillis || before.createdAtMillis != after.createdAtMillis) reject(ProblemCode.INTEGRITY_FAILURE)
            LegacyEntry(path.name, size, hash)
        }
    }

    private fun checked(path: Path): FileMetadata {
        val metadata = files.metadata(path)
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || files.canonicalize(path).parent != root.normalized()) reject(ProblemCode.INVALID_DATA)
        return metadata
    }

    override fun open(entry: LegacyEntry): Source {
        if (entry.name.isEmpty() || entry.name in setOf(".", "..") || entry.name.any { it == '/' || it == '\\' }) reject(ProblemCode.INVALID_DATA)
        val path = root / entry.name
        if (checked(path).size != entry.size) reject(ProblemCode.INTEGRITY_FAILURE)
        return files.source(path)
    }
}
