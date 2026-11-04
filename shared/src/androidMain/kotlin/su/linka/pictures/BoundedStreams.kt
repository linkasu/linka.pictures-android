package su.linka.pictures

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object BoundedStreams {
    @Throws(IOException::class)
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        return limit(input, maxBytes).copyTo(output)
    }

    fun limit(input: InputStream, maxBytes: Long): InputStream {
        require(maxBytes >= 0)
        return object : InputStream() {
            private var total = 0L

            private fun count(read: Int): Int {
                if (read > 0) {
                    if (read.toLong() > maxBytes - total) throw IOException("Stream exceeds byte limit: $maxBytes")
                    total += read
                }
                return read
            }

            override fun read(): Int = input.read().also { if (it != -1) count(1) }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                count(input.read(buffer, offset, length))

            override fun close() = input.close()
        }
    }
}
