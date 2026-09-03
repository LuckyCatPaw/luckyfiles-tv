package com.luckycatpaw.luckyfilestv.data.source

import java.io.Closeable
import java.io.InputStream

/**
 * A file that can be read at any position.
 *
 * A player does not read a video from start to end: it jumps to the container header, then
 * to an index somewhere at the back, then to wherever the user seeks. Handing it a stream
 * means downloading everything in between, which is why an open handle with offset based
 * reads is what the proxy file descriptor needs.
 *
 * Reads block and belong on a background thread.
 */
internal interface RandomAccessSource : Closeable {

    val size: Long

    /**
     * Reads at most [length] bytes starting at [fileOffset] into [destination].
     *
     * @return the number of bytes read, `0` at the end of the file.
     */
    fun read(fileOffset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int
}

/** Sequential view of a [RandomAccessSource], for callers that only want a stream. */
internal class RandomAccessInputStream(
    private val source: RandomAccessSource,
    startOffset: Long = 0L
) : InputStream() {

    private var position: Long = startOffset

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val read = source.read(position, buffer, offset, length)
        if (read <= 0) return -1

        position += read
        return read
    }

    override fun available(): Int = (source.size - position).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    override fun skip(count: Long): Long {
        if (count <= 0L) return 0L

        val skipped = count.coerceAtMost(source.size - position)
        position += skipped
        return skipped
    }

    override fun close() = source.close()
}
