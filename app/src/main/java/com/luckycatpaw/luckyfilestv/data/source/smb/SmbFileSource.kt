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
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Windows shares.
 *
 * Single operations — create a folder, rename, delete — go straight to the server. What is
 * still missing is the transfer layer writing here: it assumes local semantics for that,
 * with replacement transactions and an fsync on the parent directory, and neither exists
 * over SMB in that form.
 */
internal class SmbFileSource(
    private val shares: SmbShareStore,
    private val sessions: SmbSessionPool = SmbSessionPool()
) : FileSource {

    override val id: String = SmbShare.SCHEME

    override val capabilities: SourceCapabilities = SourceCapabilities(
        writable = true,
        randomAccessRead = true,
        // A rename on the server never silently replaces and moves no data, as long as it
        // stays inside one share.
        atomicMove = true,
        // Every attribute costs a round trip, so previews must stay throttled here.
        cheapMetadata = false,
        requiresNetwork = true
    )

    /** Configured shares, without asking any server: an offline NAS still gets its tile. */
    override suspend fun roots(): List<Volume> = shares.shares().map { share ->
        Volume(
            path = share.path,
            name = share.displayName,
            kind = VolumeKind.NETWORK,
            configId = share.id
        )
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
            displayName = if (path == target.share.path) target.share.displayName else path.name,
            // Whether the user may actually write is the server's decision, and it answers
            // with STATUS_ACCESS_DENIED when it says no. Guessing here would only hide
            // actions that work.
            writable = true,
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

    override suspend fun createDirectory(parent: SourcePath, name: String): SourcePath {
        val cleanName = validName(name, forDirectory = true)
        val created = parent.child(cleanName)
        val target = resolve(created, SourceOperation.CREATE_DIRECTORY)

        execute(SourceOperation.CREATE_DIRECTORY, created, target) { diskShare ->
            if (diskShare.folderExists(target.relativePath) || diskShare.fileExists(target.relativePath)) {
                throw SourceException.AlreadyExists(cleanName)
            }

            diskShare.mkdir(target.relativePath)
        }

        return created
    }

    override suspend fun rename(path: SourcePath, newName: String): SourcePath {
        val cleanName = validName(newName, forDirectory = false)
        val renamed = path.sibling(cleanName) ?: throw SourceException.ParentMissing(path)

        if (cleanName == path.name) return path

        val source = resolve(path, SourceOperation.RENAME)
        val destination = resolve(renamed, SourceOperation.RENAME)

        execute(SourceOperation.RENAME, path, source) { diskShare ->
            diskShare.open(
                source.relativePath,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { entry ->
                // Never replace: an occupied name has to surface as a conflict, exactly as
                // it does locally.
                entry.rename(destination.relativePath, false)
            }
        }

        return renamed
    }

    override suspend fun delete(path: SourcePath) {
        val target = resolve(path, SourceOperation.DELETE)

        execute(SourceOperation.DELETE, path, target) { diskShare ->
            if (diskShare.folderExists(target.relativePath)) {
                diskShare.rmdir(target.relativePath, true)
            } else {
                diskShare.rm(target.relativePath)
            }
        }
    }

    override suspend fun openOutput(path: SourcePath, overwrite: Boolean): OutputStream {
        val target = resolve(path, SourceOperation.WRITE)

        return execute(SourceOperation.WRITE, path, target) { diskShare ->
            val remoteFile = diskShare.openFile(
                target.relativePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                if (overwrite) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_CREATE,
                null
            )

            object : FilterOutputStream(remoteFile.outputStream) {

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    // FilterOutputStream would otherwise write byte by byte, which over the
                    // network means one request per byte.
                    out.write(buffer, offset, length)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        remoteFile.close()
                    }
                }
            }
        }
    }

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

    private fun validName(name: String, forDirectory: Boolean): String =
        runCatching { FileUtil.validateFileName(name) }
            .getOrElse { throw SourceException.InvalidName(name, forDirectory) }

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

    private data class SmbTarget(val share: SmbShare, val relativePath: String)

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
