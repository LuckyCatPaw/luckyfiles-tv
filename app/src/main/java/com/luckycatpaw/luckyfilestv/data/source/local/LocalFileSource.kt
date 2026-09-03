package com.luckycatpaw.luckyfilestv.data.source.local

import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.source.DirectoryListing
import com.luckycatpaw.luckyfilestv.data.source.FileEntry
import com.luckycatpaw.luckyfilestv.data.source.FileSource
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.RandomAccessSource
import com.luckycatpaw.luckyfilestv.data.source.SourceCapabilities
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.Volume
import com.luckycatpaw.luckyfilestv.data.source.entryComparator
import com.luckycatpaw.luckyfilestv.util.FileUtil
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device storage.
 *
 * Holds everything that used to sit in `FileRepository` and in the browsing coordinator:
 * listing, sorting, the writability of a directory and the name a storage root is shown
 * under. Callers get all of it from one background round trip instead of stat-ing the
 * directory again on the main thread.
 */
internal class LocalFileSource(
    private val volumes: LocalVolumeRepository,
    private val fileTreeWalker: FileTreeWalker = FileTreeWalker(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : FileSource {

    override val id: String = SourcePath.LOCAL_SCHEME

    override val capabilities: SourceCapabilities = SourceCapabilities(
        writable = true,
        randomAccessRead = true,
        atomicMove = true,
        cheapMetadata = true,
        requiresNetwork = false
    )

    override suspend fun roots(): List<Volume> = volumes.volumes()

    override suspend fun list(path: SourcePath, options: ListOptions): DirectoryListing = withContext(dispatcher) {
        val directory = path.canonical(SourceOperation.LIST)

        if (!directory.exists()) throw SourceException.NotFound(path, SourceOperation.LIST)
        if (!directory.isDirectory) throw SourceException.NotADirectory(path)
        if (!directory.canRead()) throw SourceException.AccessDenied(path, SourceOperation.LIST)

        val children = directory.listFiles()
            ?: throw SourceException.AccessDenied(path, SourceOperation.LIST)

        val entries = children
            .mapNotNull { child ->
                if (FileUtil.isHiddenFile(child.name, options.hideFolderJpg)) return@mapNotNull null
                child.toEntry(readSize = options.sort.needsSize)
            }
            .sortedWith(entryComparator(options.sort))

        DirectoryListing(
            // The requested location stays the identity of the screen; canonicalisation is an
            // implementation detail and must not change what the caller navigated to.
            path = path,
            displayName = displayName(path, directory),
            writable = directory.canWrite(),
            entries = entries
        )
    }

    override suspend fun stat(path: SourcePath): FileEntry? = withContext(dispatcher) {
        val file = path.toFile()
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) null else file.toEntry(readSize = true)
    }

    override suspend fun properties(path: SourcePath): FileProperties = withContext(dispatcher) {
        val file = path.canonical(SourceOperation.PROPERTIES)
        if (!file.exists()) throw SourceException.NotFound(path, SourceOperation.PROPERTIES)

        val scan = fileTreeWalker.scan(file)
        FileProperties(
            name = file.name,
            path = file.absolutePath,
            size = scan.size,
            lastModified = file.lastModified(),
            isDirectory = file.isDirectory,
            fileCount = scan.fileCount,
            folderCount = scan.directoryCount,
            extension = file.extension.takeIf { it.isNotBlank() },
            mimeType = MimeTypes.forFileName(file.name),
            unreadableDirectoryCount = scan.unreadableDirectoryCount
        )
    }

    override suspend fun createDirectory(parent: SourcePath, name: String): SourcePath = withContext(dispatcher) {
        val directory = parent.canonical(SourceOperation.CREATE_DIRECTORY)
        if (!directory.exists() || !directory.isDirectory) {
            throw SourceException.NotFound(parent, SourceOperation.CREATE_DIRECTORY)
        }
        if (!directory.canWrite()) throw SourceException.AccessDenied(parent, SourceOperation.CREATE_DIRECTORY)

        val cleanName = validName(name, forDirectory = true)
        val target = File(directory, cleanName).absoluteFile
        if (target.parentFile?.canonicalFile != directory) throw SourceException.InvalidName(cleanName, true)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw SourceException.AlreadyExists(cleanName)
        if (!target.mkdir()) throw SourceException.Failed(SourceOperation.CREATE_DIRECTORY)

        SourcePath.of(target)
    }

    override suspend fun rename(path: SourcePath, newName: String): SourcePath = withContext(dispatcher) {
        val source = path.canonical(SourceOperation.RENAME)
        val parent = source.parentFile ?: throw SourceException.ParentMissing(path)

        val cleanName = validName(newName, forDirectory = false)
        if (cleanName == source.name) return@withContext SourcePath.of(source)

        val target = File(parent, cleanName).absoluteFile
        if (target.parentFile?.canonicalFile != parent) throw SourceException.InvalidName(cleanName, false)

        try {
            FileUtil.moveWithoutReplacing(source, target)
        } catch (exists: FileAlreadyExistsException) {
            throw SourceException.AlreadyExists(cleanName, exists)
        } catch (failed: IOException) {
            throw SourceException.Failed(SourceOperation.RENAME, failed)
        }

        SourcePath.of(target)
    }

    override suspend fun delete(path: SourcePath) {
        withContext(dispatcher) {
            val file = path.toFile().absoluteFile
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw SourceException.NotFound(path, SourceOperation.DELETE)
            }

            try {
                fileTreeWalker.delete(file)
            } catch (failed: IOException) {
                throw SourceException.Failed(SourceOperation.DELETE, failed)
            }
        }
    }

    override suspend fun openInput(path: SourcePath, offset: Long): InputStream = withContext(dispatcher) {
        val file = path.toFile()
        if (!file.isFile) throw SourceException.NotFound(path, SourceOperation.READ)

        try {
            FileInputStream(file).apply { if (offset > 0L) channel.position(offset) }
        } catch (failed: IOException) {
            throw SourceException.Failed(SourceOperation.READ, failed)
        }
    }

    override suspend fun openRandomAccess(path: SourcePath): RandomAccessSource = withContext(dispatcher) {
        val file = path.toFile()
        if (!file.isFile) throw SourceException.NotFound(path, SourceOperation.READ)

        try {
            LocalRandomAccessSource(RandomAccessFile(file, "r"))
        } catch (failed: IOException) {
            throw SourceException.Failed(SourceOperation.READ, failed)
        }
    }

    override suspend fun openOutput(path: SourcePath, overwrite: Boolean): OutputStream = withContext(dispatcher) {
        val file = path.toFile()
        if (!overwrite && Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SourceException.AlreadyExists(file.name)
        }

        try {
            FileOutputStream(file)
        } catch (failed: IOException) {
            throw SourceException.Failed(SourceOperation.WRITE, failed)
        }
    }

    /**
     * Name a directory is shown under: the volume label at a storage root, the folder name
     * everywhere else. Resolving the volumes once here replaces the per-volume `realpath`
     * the title lookup used to run in the UI layer.
     */
    private suspend fun displayName(path: SourcePath, canonical: File): String {
        volumes.volumes()
            .firstOrNull { volume ->
                volume.path == path ||
                    runCatching { volume.path.toFile().canonicalPath }.getOrNull() == canonical.path
            }
            ?.let { return it.name }

        return path.name.ifBlank { path.value }
    }

    private fun File.toEntry(readSize: Boolean): FileEntry {
        val directory = isDirectory
        return FileEntry(
            path = SourcePath.of(this),
            name = name,
            isDirectory = directory,
            size = if (readSize && !directory) length() else 0L,
            lastModified = lastModified()
        )
    }

    private fun validName(name: String, forDirectory: Boolean): String =
        runCatching { FileUtil.validateFileName(name) }
            .getOrElse { throw SourceException.InvalidName(name, forDirectory) }

    private fun SourcePath.canonical(operation: SourceOperation): File = try {
        toFile().canonicalFile
    } catch (failed: IOException) {
        throw SourceException.Failed(operation, failed)
    }
}

private class LocalRandomAccessSource(private val file: RandomAccessFile) : RandomAccessSource {

    override val size: Long get() = file.length()

    override fun read(fileOffset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        file.seek(fileOffset)
        return file.read(destination, destinationOffset, length).coerceAtLeast(0)
    }

    override fun close() = file.close()
}
