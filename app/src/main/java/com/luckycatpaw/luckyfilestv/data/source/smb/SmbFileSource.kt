package com.luckycatpaw.luckyfilestv.data.source.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.source.DirectoryListing
import com.luckycatpaw.luckyfilestv.data.source.FileEntry
import com.luckycatpaw.luckyfilestv.data.source.FileSource
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.RandomAccessInputStream
import com.luckycatpaw.luckyfilestv.data.source.RandomAccessSource
import com.luckycatpaw.luckyfilestv.data.source.SourceCapabilities
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.Volume
import com.luckycatpaw.luckyfilestv.data.source.VolumeKind
import com.luckycatpaw.luckyfilestv.data.source.entryComparator
import com.luckycatpaw.luckyfilestv.util.FileUtil
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Windows shares, read only for now.
 *
 * Writing is deliberately still missing: a copy onto a share needs the transfer layer to
 * stop assuming local file semantics — atomic rename, replacement transactions, fsync on the
 * parent directory — and that is a step of its own. Until then [capabilities] says so, the
 * browser hides what it cannot do, and every write method fails loudly instead of half way.
 */
internal class SmbFileSource(
    private val shares: SmbShareStore,
    private val sessions: SmbSessionPool = SmbSessionPool()
) : FileSource {

    override val id: String = SmbShare.SCHEME

    override val capabilities: SourceCapabilities = SourceCapabilities(
        writable = false,
        randomAccessRead = true,
        atomicMove = false,
        // Every attribute costs a round trip, so previews must stay throttled here.
        cheapMetadata = false,
        requiresNetwork = true
    )

    /** Configured shares, without asking any server: an offline NAS still gets its tile. */
    override suspend fun roots(): List<Volume> = shares.shares().map { share ->
        Volume(path = share.path, name = share.displayName, kind = VolumeKind.NETWORK)
    }

    override suspend fun list(path: SourcePath, options: ListOptions): DirectoryListing {
        val target = resolve(path, SourceOperation.LIST)

        val entries = execute(SourceOperation.LIST, path, target) { diskShare ->
            diskShare.list(target.relativePath)
                .asSequence()
                .filterNot { it.fileName == CURRENT_DIRECTORY || it.fileName == PARENT_DIRECTORY }
                .filterNot { FileUtil.isHiddenFile(it.fileName, options.hideFolderJpg) }
                .map { it.toEntry(path) }
                .toList()
        }

        return DirectoryListing(
            path = path,
            displayName = if (target.isShareRoot) target.share.displayName else path.name,
            writable = false,
            entries = entries.sortedWith(entryComparator(options.sort))
        )
    }

    override suspend fun stat(path: SourcePath): FileEntry? {
        val target = resolve(path, SourceOperation.READ)

        return try {
            execute(SourceOperation.READ, path, target) { diskShare -> diskShare.entryAt(path, target.relativePath) }
        } catch (missing: SourceException.NotFound) {
            null
        }
    }

    override suspend fun properties(path: SourcePath): FileProperties {
        val target = resolve(path, SourceOperation.PROPERTIES)
        val entry = stat(path) ?: throw SourceException.NotFound(path, SourceOperation.PROPERTIES)

        val scan = if (entry.isDirectory) scan(path, target) else DirectoryScan(entry.size, 1, 0)

        return FileProperties(
            name = entry.name,
            path = path.value,
            size = scan.size,
            lastModified = entry.lastModified,
            isDirectory = entry.isDirectory,
            fileCount = scan.fileCount,
            folderCount = scan.directoryCount,
            extension = path.extension.takeIf { it.isNotBlank() },
            mimeType = MimeTypes.forFileName(entry.name),
            unreadableDirectoryCount = scan.unreadableDirectoryCount
        )
    }

    override suspend fun openInput(path: SourcePath, offset: Long): InputStream =
        RandomAccessInputStream(openRandomAccess(path), offset)

    override suspend fun openRandomAccess(path: SourcePath): RandomAccessSource {
        val target = resolve(path, SourceOperation.READ)

        return execute(SourceOperation.READ, path, target) { diskShare ->
            val remoteFile = diskShare.openFile(
                target.relativePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            SmbRandomAccessSource(remoteFile)
        }
    }

    override suspend fun createDirectory(parent: SourcePath, name: String): SourcePath =
        throw SourceException.Unsupported(WRITING)

    override suspend fun rename(path: SourcePath, newName: String): SourcePath =
        throw SourceException.Unsupported(WRITING)

    override suspend fun delete(path: SourcePath): Unit = throw SourceException.Unsupported(WRITING)

    override suspend fun openOutput(path: SourcePath, overwrite: Boolean): OutputStream =
        throw SourceException.Unsupported(WRITING)

    /**
     * Recursive size and counts.
     *
     * Every level is a round trip, so a deep tree takes noticeably longer than locally. The
     * walk checks for cancellation on each directory: leaving the properties screen has to
     * stop the traffic immediately.
     */
    private suspend fun scan(path: SourcePath, target: SmbTarget): DirectoryScan {
        var size = 0L
        var files = 0L
        var directories = 0L
        var unreadable = 0L

        val pending = ArrayDeque(listOf(target.relativePath))

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()

            val current = pending.removeFirst()

            val children = try {
                execute(SourceOperation.PROPERTIES, path, target) { diskShare -> diskShare.list(current) }
            } catch (denied: SourceException.AccessDenied) {
                unreadable++
                continue
            }

            children
                .filterNot { it.fileName == CURRENT_DIRECTORY || it.fileName == PARENT_DIRECTORY }
                .forEach { child ->
                    val childPath = joinRelative(current, child.fileName)

                    if (child.isDirectory) {
                        directories++
                        pending.addLast(childPath)
                    } else {
                        files++
                        size += child.endOfFile
                    }
                }
        }

        return DirectoryScan(
            size = size,
            fileCount = files,
            directoryCount = directories,
            unreadableDirectoryCount = unreadable
        )
    }

    private suspend fun <T> execute(
        operation: SourceOperation,
        path: SourcePath,
        target: SmbTarget,
        block: (DiskShare) -> T
    ): T = try {
        sessions.withShare(target.share, block)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw error.toSourceException(operation, path, target.share.host)
    }

    /** Splits a location into the share it belongs to and the path inside that share. */
    private suspend fun resolve(path: SourcePath, operation: SourceOperation): SmbTarget {
        val segments = path.segments
        if (segments.isEmpty()) throw SourceException.NotFound(path, operation)

        val shareName = segments.first()
        val share = shares.shares().firstOrNull { candidate ->
            candidate.host.equals(path.authority, ignoreCase = true) &&
                candidate.name.equals(shareName, ignoreCase = true)
        } ?: throw SourceException.NotFound(path, operation)

        return SmbTarget(share = share, relativePath = segments.drop(1).joinToString(SEPARATOR))
    }

    private fun DiskShare.entryAt(path: SourcePath, relativePath: String): FileEntry {
        val information = getFileInformation(relativePath)
        val standard = information.standardInformation

        return FileEntry(
            path = path,
            name = path.name,
            isDirectory = standard.isDirectory,
            size = if (standard.isDirectory) 0L else standard.endOfFile,
            lastModified = information.basicInformation.lastWriteTime.toEpochMillis()
        )
    }

    private fun FileIdBothDirectoryInformation.toEntry(parent: SourcePath): FileEntry = FileEntry(
        path = parent.child(fileName),
        name = fileName,
        isDirectory = isDirectory,
        size = if (isDirectory) 0L else endOfFile,
        lastModified = lastWriteTime.toEpochMillis()
    )

    private data class SmbTarget(val share: SmbShare, val relativePath: String) {
        val isShareRoot: Boolean get() = relativePath.isEmpty()
    }

    private data class DirectoryScan(
        val size: Long,
        val fileCount: Long,
        val directoryCount: Long,
        val unreadableDirectoryCount: Long = 0L
    )

    private companion object {
        const val SEPARATOR = "\\"
        const val CURRENT_DIRECTORY = "."
        const val PARENT_DIRECTORY = ".."
        const val WRITING = "Writing to a share"

        fun joinRelative(parent: String, name: String): String =
            if (parent.isEmpty()) name else parent + SEPARATOR + name
    }
}

private val FileIdBothDirectoryInformation.isDirectory: Boolean
    get() = (fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L

/**
 * Open handle on a share.
 *
 * The size is read once: it cannot change while the handle is open, and asking again would
 * cost a round trip on every seek a player performs.
 */
private class SmbRandomAccessSource(private val remoteFile: File) : RandomAccessSource {

    override val size: Long by lazy {
        remoteFile.fileInformation.standardInformation.endOfFile
    }

    override fun read(fileOffset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
        remoteFile.read(destination, fileOffset, destinationOffset, length).coerceAtLeast(0)

    override fun close() = remoteFile.close()
}
