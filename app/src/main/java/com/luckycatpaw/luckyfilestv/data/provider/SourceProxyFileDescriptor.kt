package com.luckycatpaw.luckyfilestv.data.provider

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import com.luckycatpaw.luckyfilestv.data.source.RandomAccessSource
import java.io.IOException

/**
 * Serves a file from a source to another app.
 *
 * A player cannot read our in-app stream, but it can read a file descriptor. Every read it
 * performs on that descriptor arrives here as an offset and a length, which maps directly
 * onto an offset based read on the source — so seeking in a video over the network costs one
 * request instead of downloading everything up to that point.
 *
 * Called on the handler thread the descriptor was opened with, never on the main thread.
 */
internal class SourceProxyFileDescriptor(private val source: RandomAccessSource) : ProxyFileDescriptorCallback() {

    override fun onGetSize(): Long = try {
        source.size
    } catch (failure: IOException) {
        throw ErrnoException("onGetSize", OsConstants.EIO, failure)
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
        readFully(offset, size, data)
    } catch (failure: IOException) {
        throw ErrnoException("onRead", OsConstants.EIO, failure)
    }

    override fun onRelease() {
        runCatching { source.close() }
    }

    /**
     * A single read on a share can come back short. Filling the buffer here keeps players
     * happy that treat a short read as the end of the file.
     */
    private fun readFully(offset: Long, size: Int, data: ByteArray): Int {
        var total = 0

        while (total < size) {
            val read = source.read(offset + total, data, total, size - total)
            if (read <= 0) break
            total += read
        }

        return total
    }
}
