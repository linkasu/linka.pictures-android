package su.linka.pictures.media

import su.linka.pictures.application.*

/** Bounds the metadata parser and every frame rectangle before a first-frame GIF decode. */
internal fun validateGifThumbnail(bytes: ByteArray, width: Int, height: Int, check: () -> Unit): OperationResult<Unit> {
    return try {
        fun invalid(): Nothing = throw GifThumbnailFailure(ProblemCode.INVALID_DATA)
        fun limit(): Nothing = throw GifThumbnailFailure(ProblemCode.LIMIT_EXCEEDED)
        if (bytes.size > 64 * 1024 * 1024) limit()
        if (bytes.size < 14) invalid()
        check()
        var position = 0
        fun read(): Int {
            if (position == bytes.size) invalid()
            return bytes[position++].toInt() and 255
        }
        fun skip(count: Int) {
            if (count > bytes.size - position) invalid()
            position += count
        }
        fun little(): Int = read() or (read() shl 8)
        val signature = bytes.copyOfRange(0, 6).decodeToString()
        if (signature != "GIF87a" && signature != "GIF89a") invalid()
        position = 6
        val logicalWidth = little()
        val logicalHeight = little()
        if (logicalWidth == 0 || logicalHeight == 0) invalid()
        if (logicalWidth !in 1..8192 || logicalHeight !in 1..8192 || logicalWidth.toLong() * logicalHeight > 16_000_000) limit()
        if (logicalWidth != width || logicalHeight != height) invalid()
        val packed = read()
        skip(2)
        if (packed and 128 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        var blocks = 0
        fun subBlocks(requireData: Boolean = false) {
            var present = false
            while (true) {
                check()
                if (++blocks > 1_000_000) limit()
                val size = read()
                if (size == 0) break
                present = true
                skip(size)
            }
            if (requireData && !present) invalid()
        }
        var frames = 0
        var commands = 0
        while (true) {
            check()
            if (++commands > 16_384) limit()
            when (read()) {
                0x2c -> {
                    if (++frames > 4096) limit()
                    val left = little()
                    val top = little()
                    val frameWidth = little()
                    val frameHeight = little()
                    if (frameWidth == 0 || frameHeight == 0 || left + frameWidth > width || top + frameHeight > height) invalid()
                    val flags = read()
                    if (flags and 128 != 0) skip(3 * (1 shl ((flags and 7) + 1)))
                    if (read() !in 2..8) invalid()
                    subBlocks(requireData = true)
                }
                0x21 -> when (read()) {
                    0xf9 -> {
                        if (read() != 4) invalid()
                        skip(4)
                        if (read() != 0) invalid()
                    }
                    else -> subBlocks()
                }
                0x3b -> {
                    if (frames == 0 || position != bytes.size) invalid()
                    break
                }
                else -> invalid()
            }
        }
        check()
        OperationResult.Completed(Unit)
    } catch (failure: GifThumbnailFailure) {
        OperationResult.Failed(Problem(failure.code))
    }
}

private class GifThumbnailFailure(val code: ProblemCode) : Exception()
