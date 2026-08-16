package com.luckycatpaw.luckyfilestv.data.common

import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeCycleException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntry
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeOutsideRootException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeStats
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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

            if (Files.isSymbolicLink(file.toPath())) {
                onEntry(
                    file.toEntry(
                        relativePath = frame.relativePath,
                        type = FileTreeEntryType.SYMBOLIC_LINK
                    )
                )
                continue
            }

            val canonical = file.canonicalFile

            if (!isSameOrChild(rootCanonical, canonical)) {
                throw FileTreeOutsideRootException(file)
            }

            if (!canonical.isDirectory) {
                onEntry(
                    canonical.toEntry(
                        relativePath = frame.relativePath,
                        type = FileTreeEntryType.FILE
                    )
                )
                continue
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

    private fun File.toEntry(relativePath: String, type: FileTreeEntryType): FileTreeEntry {
        val size = when (type) {
            FileTreeEntryType.FILE -> length().coerceAtLeast(0L)
            FileTreeEntryType.SYMBOLIC_LINK -> 0L
            FileTreeEntryType.DIRECTORY -> 0L
        }

        return FileTreeEntry(
            file = this,
            relativePath = relativePath,
            type = type,
            size = size
        )
    }

    private fun isSameOrChild(parent: File, child: File): Boolean {
        val parentPath = parent.path
        val childPath = child.path

        return childPath == parentPath ||
            childPath.startsWith(parentPath + File.separator)
    }

    private fun safeAdd(first: Long, second: Long): Long = if (Long.MAX_VALUE - first < second) {
        Long.MAX_VALUE
    } else {
        first + second
    }

    private data class WalkFrame(val file: File, val relativePath: String, val directoryComplete: Boolean)
}
