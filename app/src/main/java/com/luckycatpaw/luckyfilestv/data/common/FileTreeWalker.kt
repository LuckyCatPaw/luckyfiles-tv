package com.luckycatpaw.luckyfilestv.data.common

import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeCycleException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntry
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeOutsideRootException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeStats
import com.luckycatpaw.luckyfilestv.util.FileUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class FileTreeWalker {

    suspend fun scan(root: File): FileTreeStats {
        var size = 0L
        var fileCount = 0L
        var directoryCount = 0L
        var symbolicLinkCount = 0L
        var unreadableDirectoryCount = 0L

        walk(
            root = root,
            onEntry = { entry ->
                when (entry.type) {
                    FileTreeEntryType.DIRECTORY -> {
                        if (entry.relativePath.isNotEmpty()) {
                            directoryCount = safeAdd(directoryCount, 1L)
                        }
                    }

                    FileTreeEntryType.FILE -> {
                        fileCount = safeAdd(fileCount, 1L)
                        size = safeAdd(size, entry.size)
                    }

                    FileTreeEntryType.SYMBOLIC_LINK -> {
                        fileCount = safeAdd(fileCount, 1L)
                        symbolicLinkCount = safeAdd(symbolicLinkCount, 1L)
                    }
                }
            },
            onUnreadableDirectory = {
                unreadableDirectoryCount = safeAdd(unreadableDirectoryCount, 1L)
            }
        )

        return FileTreeStats(
            size = size,
            fileCount = fileCount,
            directoryCount = directoryCount,
            symbolicLinkCount = symbolicLinkCount,
            unreadableDirectoryCount = unreadableDirectoryCount
        )
    }

    suspend fun walk(
        root: File,
        onEntry: suspend (FileTreeEntry) -> Unit,
        onDirectoryComplete: suspend (FileTreeEntry) -> Unit = {},
        onUnreadableDirectory: suspend (File) -> Unit = { throw FileTreeReadException(it) }
    ) {
        val source = root.toPath()
            .toAbsolutePath()
            .normalize()
            .toFile()

        if (!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException(source.absolutePath)
        }

        val rootCanonical = source.canonicalFile
        val visitedDirectories = mutableSetOf<String>()
        val stack = ArrayDeque<WalkFrame>()

        stack.addLast(
            WalkFrame(
                file = source,
                relativePath = "",
                directoryComplete = false
            )
        )

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()

            val frame = stack.removeLast()
            val file = frame.file

            if (frame.directoryComplete) {
                onDirectoryComplete(
                    file.toEntry(
                        relativePath = frame.relativePath,
                        type = FileTreeEntryType.DIRECTORY
                    )
                )
                continue
            }

            val attributes = attributesOf(file)

            if (attributes?.isSymbolicLink == true) {
                onEntry(
                    file.toEntry(
                        relativePath = frame.relativePath,
                        type = FileTreeEntryType.SYMBOLIC_LINK
                    )
                )
                continue
            }

            // Also the path for an entry that vanished between the listing and now: there is
            // nothing to descend into, and reporting it as an empty file is what a separate
            // `isDirectory` call used to produce.
            if (attributes?.isDirectory != true) {
                onEntry(
                    file.toEntry(
                        relativePath = frame.relativePath,
                        type = FileTreeEntryType.FILE,
                        size = attributes?.size() ?: 0L
                    )
                )
                continue
            }

            // Resolved for directories only, because only descending can leave the tree. A
            // symbolic link is recognised above and never followed, so a plain entry inside
            // an already contained directory cannot point anywhere else — and paying a
            // `realpath` for every one of fifty thousand files to learn that is what made a
            // properties scan slow.
            val canonical = file.canonicalFile

            if (!FileUtil.isSameOrChildPath(rootCanonical.path, canonical.path)) {
                throw FileTreeOutsideRootException(file)
            }

            if (!visitedDirectories.add(canonical.path)) {
                throw FileTreeCycleException(canonical)
            }

            val directoryEntry = canonical.toEntry(
                relativePath = frame.relativePath,
                type = FileTreeEntryType.DIRECTORY
            )

            onEntry(directoryEntry)

            val completionFrame = WalkFrame(
                file = canonical,
                relativePath = frame.relativePath,
                directoryComplete = true
            )

            val children = try {
                canonical.listFiles()
            } catch (_: SecurityException) {
                null
            }

            if (children == null) {
                onUnreadableDirectory(canonical)
                stack.addLast(completionFrame)
                continue
            }

            stack.addLast(completionFrame)

            for (index in children.indices.reversed()) {
                val child = children[index]
                val relativePath = if (frame.relativePath.isEmpty()) {
                    child.name
                } else {
                    frame.relativePath + File.separator + child.name
                }

                stack.addLast(
                    WalkFrame(
                        file = child,
                        relativePath = relativePath,
                        directoryComplete = false
                    )
                )
            }
        }
    }

    suspend fun delete(root: File) {
        walk(
            root = root,
            onEntry = { entry ->
                when (entry.type) {
                    FileTreeEntryType.FILE,
                    FileTreeEntryType.SYMBOLIC_LINK -> {
                        if (!entry.file.delete()) {
                            throw IOException("File could not be deleted: ${entry.file.absolutePath}")
                        }
                    }

                    FileTreeEntryType.DIRECTORY -> Unit
                }
            },
            onDirectoryComplete = { entry ->
                if (!entry.file.delete()) {
                    throw IOException("Directory could not be deleted: ${entry.file.absolutePath}")
                }
            }
        )
    }

    /**
     * Type and size of an entry in a single `lstat`.
     *
     * The walk used to ask three times per entry — [Files.isSymbolicLink], `isDirectory` and
     * `length` — plus a `realpath`, and a directory of fifty thousand files pays each of
     * those fifty thousand times.
     *
     * @return `null` when the entry could not be read at all, which is not worth aborting a
     *   scan over: files disappear while a tree is being walked.
     */
    private fun attributesOf(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun File.toEntry(
        relativePath: String,
        type: FileTreeEntryType,
        size: Long = 0L
    ): FileTreeEntry = FileTreeEntry(
        file = this,
        relativePath = relativePath,
        type = type,
        size = if (type == FileTreeEntryType.FILE) size.coerceAtLeast(0L) else 0L
    )

    private fun safeAdd(first: Long, second: Long): Long = if (Long.MAX_VALUE - first < second) {
        Long.MAX_VALUE
    } else {
        first + second
    }

    private data class WalkFrame(val file: File, val relativePath: String, val directoryComplete: Boolean)
}
