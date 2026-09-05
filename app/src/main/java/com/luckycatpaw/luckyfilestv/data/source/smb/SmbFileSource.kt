package com.luckycatpaw.luckyfilestv.data.source.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.common.SMBRuntimeException
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/**
 * Windows shares.
 *
 * Single operations — create a folder, rename, delete — go straight to the server, and so
 * does writing: a transfer to a share streams through [openOutput]. What a share cannot
 * offer is the durability the local side has. There is no fsync for a remote directory and
 * the server decides for itself when it commits, so a copy here has no replacement
 * transaction behind it and a failed one leaves a partial file for the cleanup to remove.
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

        val entries = execute(SourceOperation.LIST, path, target, SmbCallKind.IDEMPOTENT) { diskShare ->
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
            execute(SourceOperation.READ, path, target, SmbCallKind.IDEMPOTENT) { diskShare ->
                diskShare.entryAt(path, target.relativePath)
            }
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
        val handle = SmbRandomAccessSource(target.share, target.relativePath, sessions)

        // Open once here so a missing file or a refused login surfaces as a proper error
        // rather than on the first read, deep inside another app. Not through execute: that
        // would take a share from the pool only for the handle to ask for a second one, and
        // the blocking open would occupy an IO thread while waiting for another.
        try {
            handle.openSuspending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw error.toSourceException(SourceOperation.READ, path, target.share.host)
        }

        return handle
    }

    override suspend fun createDirectory(parent: SourcePath, name: String): SourcePath {
        val cleanName = validName(name, forDirectory = true)
        val created = parent.child(cleanName)
        val target = resolve(created, SourceOperation.CREATE_DIRECTORY)

        execute(SourceOperation.CREATE_DIRECTORY, created, target, SmbCallKind.MUTATING) { diskShare ->
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

        execute(SourceOperation.RENAME, path, source, SmbCallKind.MUTATING) { diskShare ->
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

    /**
     * Server-side move, which is why a file changing folders on a share costs no traffic.
     *
     * Only within one share: SMB renames inside a tree, not across them. Anything else
     * falls back to copy and delete in the transfer layer.
     */
    override suspend fun move(from: SourcePath, to: SourcePath) {
        val source = resolve(from, SourceOperation.RENAME)
        val destination = resolve(to, SourceOperation.RENAME)

        if (source.share.sessionKey != destination.share.sessionKey) {
            throw SourceException.Unsupported("Moving between shares")
        }

        execute(SourceOperation.RENAME, from, source, SmbCallKind.MUTATING) { diskShare ->
            diskShare.open(
                source.relativePath,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { entry -> entry.rename(destination.relativePath, false) }
        }
    }

    override suspend fun delete(path: SourcePath) {
        val target = resolve(path, SourceOperation.DELETE)

        execute(SourceOperation.DELETE, path, target, SmbCallKind.MUTATING) { diskShare ->
            if (diskShare.folderExists(target.relativePath)) {
                diskShare.rmdir(target.relativePath, true)
            } else {
                diskShare.rm(target.relativePath)
            }
        }
    }

    /**
     * The returned stream outlives this call, so it holds the session itself.
     *
     * Without the lease the pool would consider the share free again the moment the stream
     * exists, and a network change during a long copy would close it underneath the writer.
     */
    override suspend fun openOutput(path: SourcePath, overwrite: Boolean): OutputStream {
        // Checked here as well as in the rename and folder paths, because a copy reaches a
        // share with a name the local side was happy with. Without this the user gets the
        // server's status code for a file called "Season 1: Pilot.mkv".
        validName(path.name, forDirectory = false)

        val target = resolve(path, SourceOperation.WRITE)
        val lease = lease(SourceOperation.WRITE, path, target)

        return mapping(SourceOperation.WRITE, path, target, onFailure = lease::close) {
            val remoteFile = lease.diskShare.openFile(
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
                        try {
                            remoteFile.close()
                        } finally {
                            lease.close()
                        }
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
                execute(SourceOperation.PROPERTIES, path, target, SmbCallKind.IDEMPOTENT) { diskShare ->
                    diskShare.list(current)
                }
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

    /**
     * @param kind whether the pool may repeat [block] on a fresh session when the transport
     *   drops mid-call. Everything that writes has to say so, see [SmbCallKind].
     */
    private suspend fun <T> execute(
        operation: SourceOperation,
        path: SourcePath,
        target: SmbTarget,
        kind: SmbCallKind,
        block: (DiskShare) -> T
    ): T = try {
        sessions.withShare(target.share, kind, block)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw error.toSourceException(operation, path, target.share.host)
    }

    /** Borrows a session for something that keeps working after this call returns. */
    private suspend fun lease(
        operation: SourceOperation,
        path: SourcePath,
        target: SmbTarget
    ): SmbShareLease = mapping(operation, path, target) { sessions.lease(target.share) }

    /**
     * Runs [block] with the same error vocabulary [execute] produces, and hands anything
     * already borrowed back before the failure leaves the source.
     */
    private inline fun <T> mapping(
        operation: SourceOperation,
        path: SourcePath,
        target: SmbTarget,
        onFailure: () -> Unit = {},
        block: () -> T
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        onFailure()
        throw cancelled
    } catch (error: Throwable) {
        onFailure()
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

    private fun validName(name: String, forDirectory: Boolean): String {
        val clean = runCatching { FileUtil.validateFileName(name) }
            .getOrElse { throw SourceException.InvalidName(name, forDirectory) }

        if (!isAcceptedByWindows(clean)) throw SourceException.InvalidName(name, forDirectory)

        return clean
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
 * Whether a Windows server will take this name.
 *
 * SMB inherits the Win32 rules, and breaking them fails at the server with a status code
 * that means nothing to the user. Rejecting the name here turns it into the same message a
 * local rename produces.
 *
 * Reserved device names are matched on the part before the first dot, which is how Windows
 * resolves them: `NUL.txt` is the null device, not a text file.
 */
private fun isAcceptedByWindows(name: String): Boolean {
    if (name.any { it in RESERVED_CHARACTERS || it.code < 0x20 }) return false
    if (name.endsWith('.') || name.endsWith(' ')) return false

    return name.substringBefore('.').uppercase() !in RESERVED_DEVICE_NAMES
}

private const val RESERVED_CHARACTERS = "<>:\"|?*"

private val RESERVED_DEVICE_NAMES: Set<String> =
    setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } +
        (1..9).map { "LPT$it" }

/**
 * Open handle on a share.
 *
 * Reopens itself when the session underneath disappears. A file played by another app is
 * read for as long as the film lasts, and in that time a server drops an idle client, the
 * device changes network or the pool is invalidated — after which smbj answers every further
 * read with "DiskShare has already been closed". Without recovery here, playback ends there
 * and never resumes: the handle bypasses the pool, so its retry cannot help.
 */
private class SmbRandomAccessSource(
    private val share: SmbShare,
    private val relativePath: String,
    private val sessions: SmbSessionPool
) : RandomAccessSource {

    /**
     * Handle and lease belong together and are always swapped as a pair: reads arrive on the
     * descriptor's thread while a reopen may run on a coroutine, and a half-replaced pair
     * would either read from a closed handle or leak the session behind it.
     */
    private val lock = Any()
    private var open: OpenHandle? = null

    override val size: Long by lazy {
        withHandle { it.fileInformation.standardInformation.endOfFile }
    }

    override fun read(fileOffset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
        withHandle { it.read(destination, fileOffset, destinationOffset, length) }.coerceAtLeast(0)

    suspend fun openSuspending(): File {
        current()?.let { return it.file }

        // The handle stays open for as long as the film lasts, so the session has to be
        // held for that time rather than returned after the open.
        val lease = sessions.lease(share)

        val opened = try {
            OpenHandle(
                lease = lease,
                file = lease.diskShare.openFile(
                    relativePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
            )
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }

        return install(opened).file
    }

    /**
     * Blocking variant for the reopen during a read.
     *
     * Reads arrive on the descriptor's own thread, which is not a coroutine and exists to be
     * blocked. Only that path uses this.
     */
    private fun openBlocking(): File = current()?.file ?: runBlocking { openSuspending() }

    override fun close() = discard()

    /** One retry on a fresh handle; a second failure is a real one and reaches the caller. */
    private fun <T> withHandle(block: (File) -> T): T = try {
        block(openBlocking())
    } catch (dropped: SMBRuntimeException) {
        discard()
        block(openBlocking())
    } catch (dropped: IOException) {
        discard()
        block(openBlocking())
    }

    private fun current(): OpenHandle? = synchronized(lock) { open }

    /** Keeps whichever handle got there first, so a race opens no second session. */
    private fun install(opened: OpenHandle): OpenHandle {
        val installed = synchronized(lock) {
            open ?: opened.also { open = it }
        }

        if (installed !== opened) opened.close()

        return installed
    }

    private fun discard() {
        synchronized(lock) { open.also { open = null } }?.close()
    }

    private class OpenHandle(private val lease: SmbShareLease, val file: File) {

        fun close() {
            try {
                runCatching { file.close() }
            } finally {
                lease.close()
            }
        }
    }
}
