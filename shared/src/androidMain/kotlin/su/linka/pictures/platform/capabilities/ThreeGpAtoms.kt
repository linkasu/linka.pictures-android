package su.linka.pictures.platform.capabilities

import java.io.RandomAccessFile
import su.linka.pictures.application.ProblemCode

/** Bounded container/reference validation only; sample tables and mdat remain opaque. */
internal class ThreeGpAtoms(private val source: RandomAccessFile, private val check: () -> Unit) {
    private data class Atom(val type: String, val start: Long, val payload: Long, val end: Long)
    private var atoms = 0
    private var media = false
    private var movie = false

    fun validate() {
        val end = source.length()
        if (end > MediaContainer.MAX_BYTES) limit()
        source.seek(0)
        walk(end, 0, "")
        if (!media || !movie) MediaContainer.invalid()
    }

    private fun limit(): Nothing = throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
    private fun type(): String = ByteArray(4).also(source::readFully).toString(Charsets.US_ASCII)
    private fun next(parentEnd: Long): Atom {
        check()
        if (++atoms > 4096) limit()
        val start = source.filePointer
        val remaining = parentEnd - start
        if (remaining < 8) MediaContainer.invalid()
        val shortSize = source.readInt().toLong() and 0xffffffffL
        val kind = type()
        var header = 8
        val size = when (shortSize) {
            0L -> remaining
            1L -> {
                if (remaining < 16) MediaContainer.invalid()
                header = 16
                source.readLong() // Negative unsigned64 values cannot be valid within our bounded source.
            }
            else -> shortSize
        }
        if (size < header || size > remaining) MediaContainer.invalid()
        return Atom(kind, start, source.filePointer, start + size) // Addition is safe after the subtraction-based bound.
    }

    private fun walk(end: Long, depth: Int, parent: String) {
        if (depth > 8) limit()
        while (source.filePointer < end) {
            val atom = next(end)
            if (depth == 0 && atom.start == 0L) {
                if (atom.type != "ftyp") MediaContainer.unsupported()
                if (atom.end - atom.payload < 8 || (atom.end - atom.payload) % 4 != 0L) MediaContainer.invalid()
                if (!type().startsWith("3g")) MediaContainer.unsupported()
            }
            if (parent == "moov" && atom.type == "rmra") MediaContainer.unsupported()
            when (atom.type) {
                "mdat" -> if (depth == 0 && atom.end > atom.payload) media = true
                "moov" -> {
                    if (atom.end - atom.start > 8L * 1024 * 1024) limit()
                    if (depth == 0 && atom.end > atom.payload) movie = true
                    walk(atom.end, depth + 1, atom.type)
                }
                "trak", "mdia", "minf", "dinf" -> if (depth > 0) walk(atom.end, depth + 1, atom.type)
                "dref" -> if (depth > 0) references(atom.end, depth + 1)
            }
            source.seek(atom.end) // Unknown leaf boxes and all mdat bytes are skipped, never scanned for FourCC strings.
        }
        if (source.filePointer != end) MediaContainer.invalid()
    }

    private fun references(end: Long, depth: Int) {
        if (depth > 8) limit()
        if (end - source.filePointer < 8) MediaContainer.invalid()
        if (source.readInt() != 0) MediaContainer.invalid() // dref FullBox version0, reserved flags0.
        val count = source.readInt().toLong() and 0xffffffffL
        if (count == 0L) MediaContainer.invalid()
        if (count > 4096 - atoms) limit()
        repeat(count.toInt()) {
            val entry = next(end)
            if (entry.type != "url ") MediaContainer.unsupported()
            if (entry.end - entry.payload < 4) MediaContainer.invalid()
            val fullBox = source.readInt()
            if (fullBox ushr 24 != 0) MediaContainer.invalid()
            if ((fullBox and 0xffffff) != 1 || source.filePointer != entry.end) MediaContainer.unsupported()
        }
        if (source.filePointer != end) MediaContainer.invalid() // Count must describe the complete dref payload.
    }
}
