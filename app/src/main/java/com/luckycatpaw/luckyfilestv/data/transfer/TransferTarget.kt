package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.util.DirectorySync
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * The write side of a transfer.
 *
 * Local storage keeps every guarantee it had: a file is created exclusively, the data is
 * forced to disk, and the directory entry is flushed afterwards. A share can offer none of
 * that — there is no fsync for a remote directory, and a server decides for itself when it
 * commits. What both can do is create, write and set a name, and that is what this describes.
 */
internal sealed interface TransferTarget {

    val pathValue: String

    val name: String

    val isLocal: Boolean

    suspend fun exists(relativePath: String = ""): Boolean

    suspend fun createDirectory(relativePath: String)

    /** Fails when something is already there, so a transfer never overwrites unnoticed. */
    suspend fun openOutput(relativePath: String): OutputStream

    /** Best effort: a share may ignore it, and a wrong date is better than a failed copy. */
    suspend fun setLastModified(relativePath: String, lastModified: Long)

    /** Flushes the directory entry where the platform supports it. */
    suspend fun flush()

    /** Removes what was created, used to clean up after a cancelled or failed transfer. */
    suspend fun deleteTree()

    class Local(val file: File) : TransferTarget {

        override val pathValue: String get() = file.absolutePath

        override val name: String get() = file.name

        override val isLocal: Boolean get() = true

        override suspend fun exists(relativePath: String): Boolean =
            Files.exists(resolve(relativePath).toPath(), LinkOption.NOFOLLOW_LINKS)

        override suspend fun createDirectory(relativePath: String) {
            Files.createDirectory(resolve(relativePath).toPath())
        }

        override suspend fun openOutput(relativePath: String): OutputStream {
            val channel = FileChannel.open(
                resolve(relativePath).toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )

            return ForcingOutputStream(channel)
        }

        override suspend fun setLastModified(relativePath: String, lastModified: Long) {
            resolve(relativePath).setLastModified(lastModified)
        }

        override suspend fun flush() {
            DirectorySync.sync(file.parentFile)
        }

        override suspend fun deleteTree() {
            resolve("").deleteRecursively()
        }

        fun resolve(relativePath: String): File = if (relativePath.isEmpty()) {
            file
        } else {
            File(file, relativePath)
        }

        /** Keeps the write-through behaviour the engine relied on before the split. */
        private class ForcingOutputStream(private val channel: FileChannel) : OutputStream() {

            private val delegate = Channels.newOutputStream(channel)

            override fun write(byte: Int) = delegate.write(byte)

            override fun write(buffer: ByteArray, offset: Int, length: Int) =
                delegate.write(buffer, offset, length)

            override fun close() {
                try {
                    delegate.flush()
                    channel.force(true)
                } finally {
                    channel.close()
                }
            }
        }
    }

    class Remote(val path: SourcePath, private val sources: FileSourceRegistry) : TransferTarget {

        override val pathValue: String get() = path.value

        override val name: String get() = path.name

        override val isLocal: Boolean get() = false

        override suspend fun exists(relativePath: String): Boolean =
            sources.source(path).stat(resolve(relativePath)) != null

        override suspend fun createDirectory(relativePath: String) {
            val target = resolve(relativePath)
            val parent = target.parent ?: throw IllegalStateException(target.value)

            sources.source(path).createDirectory(parent, target.name)
        }

        override suspend fun openOutput(relativePath: String): OutputStream =
            sources.source(path).openOutput(resolve(relativePath), overwrite = false)

        /** SMB carries a write time, but setting it needs a separate call this does not make. */
        override suspend fun setLastModified(relativePath: String, lastModified: Long) = Unit

        /** The server decides when it commits; there is nothing to flush from here. */
        override suspend fun flush() = Unit

        override suspend fun deleteTree() {
            runCatching { sources.source(path).delete(path) }
        }

        private fun resolve(relativePath: String): SourcePath = if (relativePath.isEmpty()) {
            path
        } else {
            relativePath.split('/').fold(path) { current, segment -> current.child(segment) }
        }
    }
}
