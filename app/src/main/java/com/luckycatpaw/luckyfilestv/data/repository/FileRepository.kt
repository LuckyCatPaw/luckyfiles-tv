package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import android.webkit.MimeTypeMap
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File as JavaFile
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.name

class FileRepository(
    private val context: Context,
    private val fileTreeWalker: FileTreeWalker = FileTreeWalker()
) {

    suspend fun getItems(
        path: String,
        hideFolderJpg: Boolean = true,
        sortMode: FileSortMode = FileSortMode.NAME,
        sortAscending: Boolean = true,
        foldersFirst: Boolean = true
    ): Result<List<BrowserItem>> = cancellableResult {
        withContext(Dispatchers.IO) {
            val directoryPath = JavaFile(path).toPath()

            require(Files.exists(directoryPath) && Files.isDirectory(directoryPath)) {
                context.getString(R.string.file_or_folder_missing)
            }

            val listedFiles = mutableListOf<ListedFile>()

            Files.newDirectoryStream(directoryPath).use { stream ->
                for (itemPath in stream) {
                    currentCoroutineContext().ensureActive()

                    val name = itemPath.name
                    if (itemPath.isHidden()) continue
                    if (hideFolderJpg && name.equals("folder.jpg", ignoreCase = true)) continue

                    val isDirectory = itemPath.isDirectory()

                    listedFiles += ListedFile(
                        absolutePath = itemPath.toAbsolutePath().toString(),
                        name = name,
                        isDirectory = isDirectory,
                        size = if (sortMode == FileSortMode.SIZE && !isDirectory) {
                            runCatching { Files.size(itemPath) }.getOrDefault(0L)
                        } else {
                            0L
                        },
                        lastModified = if (sortMode == FileSortMode.DATE) {
                            runCatching { itemPath.getLastModifiedTime().toMillis() }.getOrDefault(0L)
                        } else {
                            0L
                        },
                        extension = if (sortMode == FileSortMode.TYPE && !isDirectory) {
                            itemPath.extension.lowercase()
                        } else {
                            ""
                        }
                    )
                }
            }

            listedFiles.sortWith(fileComparator(sortMode, sortAscending, foldersFirst))

            listedFiles.map { listed ->
                if (listed.isDirectory) {
                    BrowserItem.Folder(
                        name = listed.name,
                        path = listed.absolutePath
                    )
                } else {
                    BrowserItem.File(
                        name = listed.name,
                        path = listed.absolutePath
                    )
                }
            }
        }
    }

    suspend fun getProperties(path: String): Result<FileProperties> = cancellableResult {
        val file = JavaFile(path).absoluteFile

        require(Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            context.getString(R.string.file_or_folder_missing)
        }

        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            val scan = fileTreeWalker.scan(file)

            FileProperties(
                name = file.name,
                path = file.absolutePath,
                isDirectory = true,
                size = scan.size,
                lastModified = file.lastModified(),
                fileCount = scan.fileCount,
                folderCount = scan.directoryCount,
                extension = null,
                mimeType = null
            )
        } else {
            val extension = file.extension.lowercase().takeIf { it.isNotBlank() }

            FileProperties(
                name = file.name,
                path = file.absolutePath,
                isDirectory = false,
                size = if (Files.isSymbolicLink(file.toPath())) {
                    0L
                } else {
                    file.length()
                },
                lastModified = file.lastModified(),
                fileCount = 1,
                folderCount = 0,
                extension = extension,
                mimeType = extension?.let {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
                }
            )
        }
    }

    suspend fun rename(path: String, newName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val source = JavaFile(path).absoluteFile
            require(Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                context.getString(R.string.file_or_folder_missing)
            }

            val parent = source.parentFile?.canonicalFile
                ?: error(context.getString(R.string.parent_folder_missing))

            val cleanName = validateName(newName)

            if (cleanName == source.name) {
                return@runCatching source.absolutePath
            }

            val target = JavaFile(parent, cleanName).absoluteFile

            require(target.parentFile?.canonicalFile == parent) { context.getString(R.string.invalid_name) }
            require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                context.getString(R.string.already_exists, cleanName)
            }
            check(source.renameTo(target)) { context.getString(R.string.rename_failed) }

            target.absolutePath
        }
    }

    suspend fun delete(path: String): Result<Unit> = cancellableResult {
        withContext(Dispatchers.IO) {
            val source = JavaFile(path).absoluteFile
            require(Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                context.getString(R.string.file_or_folder_missing)
            }
            fileTreeWalker.delete(source)
        }
    }

    suspend fun createFolder(parentPath: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = requireDirectory(parentPath)
            val cleanName = validateName(name)
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
    ): Comparator<ListedFile> {
        return Comparator { a, b ->
            if (foldersFirst && a.isDirectory != b.isDirectory) {
                return@Comparator if (a.isDirectory) -1 else 1
            }

            val primary = when (sortMode) {
                FileSortMode.NAME -> String.CASE_INSENSITIVE_ORDER.compare(
                    a.name,
                    b.name
                )

                FileSortMode.DATE -> a.lastModified.compareTo(b.lastModified)

                FileSortMode.SIZE -> a.size.compareTo(b.size)

                FileSortMode.TYPE -> String.CASE_INSENSITIVE_ORDER.compare(
                    a.extension,
                    b.extension
                )
            }

            val orderedPrimary = if (ascending) primary else -primary

            if (orderedPrimary != 0) {
                orderedPrimary
            } else {
                val fallback = String.CASE_INSENSITIVE_ORDER.compare(
                    a.name,
                    b.name
                )
                if (ascending) fallback else -fallback
            }
        }
    }

    private fun requireDirectory(path: String): JavaFile {
        val directory = JavaFile(path).canonicalFile

        require(directory.exists() && directory.isDirectory) {
            context.getString(R.string.target_folder_missing)
        }

        require(directory.canWrite()) {
            context.getString(R.string.target_read_only)
        }

        return directory
    }

    private fun validateName(name: String): String {
        val clean = name.trim()

        require(clean.isNotBlank()) { context.getString(R.string.name_empty) }
        require(clean != "." && clean != "..") { context.getString(R.string.invalid_name) }

        require('/' !in clean && '\\' !in clean && '\u0000' !in clean) {
            context.getString(R.string.name_invalid_chars)
        }

        return clean
    }

    private suspend fun <T> cancellableResult(
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
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
