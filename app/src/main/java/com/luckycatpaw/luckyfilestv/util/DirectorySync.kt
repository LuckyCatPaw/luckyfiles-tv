package com.luckycatpaw.luckyfilestv.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor

/**
 * Flushes directory metadata to stable storage.
 *
 * `fsync(2)` on a file only covers that file, not the directory entry pointing at it. After a
 * `create`, `rename` or `unlink` the containing directory therefore has to be synced separately,
 * otherwise a crash or power loss can resurrect the old directory listing even though the file
 * data itself was durable.
 *
 * Doing this through [java.nio.channels.FileChannel] is not dependable: whether a channel may be
 * opened on a directory at all is undocumented and has changed between platform versions
 * (OpenJDK disallowed it in JDK 9 and re-allowed it afterwards). [Os.open] plus [Os.fsync] is the
 * documented Android path and expresses the intent directly.
 */
internal object DirectorySync {

    /**
     * Opens [directory] read-only and fsyncs the descriptor.
     *
     * @return `true` if the metadata reached stable storage. `false` means the guarantee is
     *   missing — some filesystems (several FUSE and network mounts) reject `fsync(2)` on a
     *   directory with `EINVAL`, which is not a reason to fail the surrounding operation.
     */
    fun sync(directory: File?): Boolean {
        val path = directory?.path ?: return false
        var descriptor: FileDescriptor? = null

        return try {
            descriptor = Os.open(path, OsConstants.O_RDONLY, 0)
            Os.fsync(descriptor)
            true
        } catch (e: ErrnoException) {
            Log.w(LOG_TAG, "Directory metadata could not be flushed for $path: ${e.message}")
            false
        } finally {
            descriptor?.let { openDescriptor ->
                runCatching { Os.close(openDescriptor) }
            }
        }
    }

    /** Convenience for the common case of making a newly created or renamed entry durable. */
    fun syncParentOf(file: File): Boolean = sync(file.parentFile)

    private const val LOG_TAG = "TVFM-DirectorySync"
}
