package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.util.FileUtil
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File as JavaFile
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FileRepository(
    private val context: Context,
    private val fileTreeWalker: FileTreeWalker = FileTreeWalker()
) {
    suspend fun getItems(
        path: String,
        hideFolderJpg: Boolean,
        sortMode: FileSortMode,
        sortAscending: Boolean,
        foldersFirst: Boolean
    ): Result<List<BrowserItem>> = FileUtil.runCancellable {
        withContext(Dispatchers.IO) {
            val directory = JavaFile(path).canonicalFile
            require(directory.exists() && directory.isDirectory) {
                context.getString(R.string.target_folder_missing)
            }
            require(directory.canRead()) {
                context.getString(R.string.folder_load_failed)
            }

            val children = directory.listFiles()
                ?: error(context.getString(R.string.folder_load_failed))

            children
                .mapNotNull { file ->
                    if (FileUtil.isHiddenFile(file.name, hideFolderJpg)) {
                        return@mapNotNull null
                    }

                    val isDirectory = file.isDirectory
                    val size = if (sortMode == FileSortMode.SIZE && !isDirectory) file.length() else 0L

                    ListedFile(
                        absolutePath = file.absolutePath,
                        name = file.name,
                        isDirectory = isDirectory,
                        size = size,
                        lastModified = file.lastModified(),
                        extension = file.extension.lowercase(Locale.ROOT)
                    )
                }
                .sortedWith(fileComparator(sortMode, sortAscending, foldersFirst))
                .map { file ->
                    if (file.isDirectory) {
                        BrowserItem.Folder(file.name, file.absolutePath)
                    } else {
                        BrowserItem.File(file.name, file.absolutePath)
                    }
                }
        }
    }

    suspend fun getProperties(path: String): Result<FileProperties> = FileUtil.runCancellable {
        withContext(Dispatchers.IO) {
            val file = JavaFile(path).canonicalFile
            if (!file.exists()) error(context.getString(R.string.file_or_folder_missing))

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
    }

    suspend fun rename(path: String, newName: String): Result<String> = FileUtil.runCancellable {
        withContext(Dispatchers.IO) {
            val source = JavaFile(path).canonicalFile
            val parent = source.parentFile ?: error(context.getString(R.string.parent_folder_missing))

            val cleanName = runCatching { FileUtil.validateFileName(newName) }
                .getOrElse { throw IllegalArgumentException(context.getString(R.string.invalid_name)) }

            if (cleanName == source.name) return@withContext source.absolutePath

            val target = JavaFile(parent, cleanName).absoluteFile
            require(target.parentFile?.canonicalFile == parent) { context.getString(R.string.invalid_name) }

            try {
                FileUtil.moveWithoutReplacing(source, target)
            } catch (exists: FileAlreadyExistsException) {
                throw IllegalStateException(
                    context.getString(R.string.already_exists, cleanName),
                    exists
                )
            } catch (renameFailed: IOException) {
                throw IllegalStateException(context.getString(R.string.rename_failed), renameFailed)
            }

            target.absolutePath
        }
    }

    suspend fun delete(path: String): Result<Unit> = FileUtil.runCancellable {
        withContext(Dispatchers.IO) {
            val source = JavaFile(path).absoluteFile
            require(Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                context.getString(R.string.file_or_folder_missing)
            }
            fileTreeWalker.delete(source)
        }
    }

    suspend fun createFolder(parentPath: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        FileUtil.runCancellable {
            val parent = requireDirectory(parentPath)
            val cleanName = runCatching { FileUtil.validateFileName(name) }
                .getOrElse { throw IllegalArgumentException(context.getString(R.string.invalid_folder_name)) }

            val target = JavaFile(parent, cleanName).absoluteFile
            require(target.parentFile?.canonicalFile == parent) { context.getString(R.string.invalid_folder_name) }
            require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                context.getString(R.string.already_exists, cleanName)
            }
            check(target.mkdir()) { context.getString(R.string.folder_create_failed) }

            target.absolutePath
        }
    }

    private fun fileComparator(
        sortMode: FileSortMode,
        ascending: Boolean,
        foldersFirst: Boolean
    ): Comparator<ListedFile> = Comparator { a, b ->
        if (foldersFirst && a.isDirectory != b.isDirectory) {
            return@Comparator if (a.isDirectory) -1 else 1
        }

        val primary = when (sortMode) {
            FileSortMode.NAME -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
            FileSortMode.DATE -> a.lastModified.compareTo(b.lastModified)
            FileSortMode.SIZE -> a.size.compareTo(b.size)
            FileSortMode.TYPE -> String.CASE_INSENSITIVE_ORDER.compare(a.extension, b.extension)
        }

        val result = if (ascending) primary else -primary
        if (result !=
            0
        ) {
            result
        } else {
            String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name).let { if (ascending) it else -it }
        }
    }

    private fun requireDirectory(path: String): JavaFile {
        val directory = JavaFile(path).canonicalFile
        require(directory.exists() && directory.isDirectory) { context.getString(R.string.target_folder_missing) }
        require(directory.canWrite()) { context.getString(R.string.target_read_only) }
        return directory
    }

    private data class ListedFile(
        val absolutePath: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val extension: String
    )
}
