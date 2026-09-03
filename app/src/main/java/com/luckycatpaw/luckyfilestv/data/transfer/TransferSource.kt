package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntry
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.SortOptions
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One entry of a tree that is being copied, independent of where it is read from. */
internal data class TransferEntry(
    /** Path below the root of the transfer, empty for the root itself. */
    val relativePath: String,
    val type: FileTreeEntryType,
    val lastModified: Long,
    private val open: suspend () -> InputStream
) {

    suspend fun openInput(): InputStream = open()
}

/** Size and shape of a tree, read before the transfer so progress and free space are known. */
internal data class TransferScan(val size: Long, val symbolicLinkCount: Long)

/**
 * The read side of a transfer.
 *
 * Only where the bytes come from differs between a local disk and a share; how they are
 * written stays the business of the engine, including its replacement transactions and the
 * flush to disk. Splitting it here keeps that safety net untouched by network sources.
 */
internal sealed interface TransferSource {

    val name: String

    /** The location as it travels through the app, e.g. for issue messages. */
    val pathValue: String

    val isLocal: Boolean

    suspend fun exists(): Boolean

    suspend fun isDirectory(): Boolean

    suspend fun scan(): TransferScan

    suspend fun walk(
        onEntry: suspend (TransferEntry) -> Unit,
        onDirectoryComplete: suspend (TransferEntry) -> Unit,
        onUnreadableDirectory: suspend (String) -> Unit
    )

    class Local(val file: File, private val fileTreeWalker: FileTreeWalker) : TransferSource {

        override val name: String get() = file.name

        override val pathValue: String get() = file.absolutePath

        override val isLocal: Boolean get() = true

        override suspend fun exists(): Boolean = file.exists()

        override suspend fun isDirectory(): Boolean = file.isDirectory

        override suspend fun scan(): TransferScan = fileTreeWalker.scan(file).let {
            TransferScan(size = it.size, symbolicLinkCount = it.symbolicLinkCount)
        }

        /**
         * Delegates unchanged to the existing walker: cycle detection, symbolic links and
         * unreadable directories keep behaving exactly as before.
         */
        override suspend fun walk(
            onEntry: suspend (TransferEntry) -> Unit,
            onDirectoryComplete: suspend (TransferEntry) -> Unit,
            onUnreadableDirectory: suspend (String) -> Unit
        ) {
            fileTreeWalker.walk(
                root = file,
                onEntry = { entry -> onEntry(toTransferEntry(entry)) },
                onDirectoryComplete = { entry -> onDirectoryComplete(toTransferEntry(entry)) },
                onUnreadableDirectory = { directory -> onUnreadableDirectory(directory.absolutePath) }
            )
        }

        private fun toTransferEntry(entry: FileTreeEntry) = TransferEntry(
            relativePath = entry.relativePath,
            type = entry.type,
            lastModified = entry.file.lastModified(),
            open = { entry.file.inputStream() }
        )
    }

    /**
     * A tree on a share.
     *
     * Every directory level costs a request, so the walk reuses the listings it already
     * fetched instead of asking for sizes and dates a second time. Symbolic links do not
     * exist here: a share reports plain files and directories.
     */
    class Remote(
        val path: SourcePath,
        private val sources: FileSourceRegistry
    ) : TransferSource {

        override val name: String get() = path.name

        override val pathValue: String get() = path.value

        override val isLocal: Boolean get() = false

        override suspend fun exists(): Boolean = sources.source(path).stat(path) != null

        override suspend fun isDirectory(): Boolean = sources.source(path).stat(path)?.isDirectory == true

        override suspend fun scan(): TransferScan {
            var size = 0L

            walkTree(
                onFile = { _, entrySize, _ -> size += entrySize },
                onDirectory = { _, _ -> },
                onDirectoryComplete = { _, _ -> },
                onUnreadable = { }
            )

            return TransferScan(size = size, symbolicLinkCount = 0L)
        }

        override suspend fun walk(
            onEntry: suspend (TransferEntry) -> Unit,
            onDirectoryComplete: suspend (TransferEntry) -> Unit,
            onUnreadableDirectory: suspend (String) -> Unit
        ) {
            walkTree(
                onFile = { relative, _, lastModified ->
                    onEntry(entry(relative, FileTreeEntryType.FILE, lastModified))
                },
                onDirectory = { relative, lastModified ->
                    onEntry(entry(relative, FileTreeEntryType.DIRECTORY, lastModified))
                },
                onDirectoryComplete = { relative, lastModified ->
                    onDirectoryComplete(entry(relative, FileTreeEntryType.DIRECTORY, lastModified))
                },
                onUnreadable = { onUnreadableDirectory(it) }
            )
        }

        private fun entry(relativePath: String, type: FileTreeEntryType, lastModified: Long) = TransferEntry(
            relativePath = relativePath,
            type = type,
            lastModified = lastModified,
            open = { sources.source(path).openInput(childOf(relativePath)) }
        )

        private fun childOf(relativePath: String): SourcePath = if (relativePath.isEmpty()) {
            path
        } else {
            relativePath.split('/').fold(path) { current, segment -> current.child(segment) }
        }

        private suspend fun walkTree(
            onFile: suspend (String, Long, Long) -> Unit,
            onDirectory: suspend (String, Long) -> Unit,
            onDirectoryComplete: suspend (String, Long) -> Unit,
            onUnreadable: suspend (String) -> Unit
        ) {
            val source = sources.source(path)
            val root = source.stat(path) ?: throw NoSuchElementException(path.value)

            if (!root.isDirectory) {
                onFile("", root.size, root.lastModified)
                return
            }

            onDirectory("", root.lastModified)
            descend("", root.lastModified, onFile, onDirectory, onDirectoryComplete, onUnreadable)
            onDirectoryComplete("", root.lastModified)
        }

        private suspend fun descend(
            relativePath: String,
            lastModified: Long,
            onFile: suspend (String, Long, Long) -> Unit,
            onDirectory: suspend (String, Long) -> Unit,
            onDirectoryComplete: suspend (String, Long) -> Unit,
            onUnreadable: suspend (String) -> Unit
        ) {
            currentCoroutineContext().ensureActive()

            val source = sources.source(path)

            val children = try {
                source.list(childOf(relativePath), LIST_EVERYTHING).entries
            } catch (unreadable: Exception) {
                onUnreadable(childOf(relativePath).value)
                return
            }

            children.forEach { child ->
                val childRelative = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"

                if (child.isDirectory) {
                    onDirectory(childRelative, child.lastModified)
                    descend(childRelative, child.lastModified, onFile, onDirectory, onDirectoryComplete, onUnreadable)
                    onDirectoryComplete(childRelative, child.lastModified)
                } else {
                    onFile(childRelative, child.size, child.lastModified)
                }
            }
        }

        private companion object {

            /** A copy takes everything, including what the browser hides. */
            val LIST_EVERYTHING = ListOptions(sort = SortOptions(), hideFolderJpg = false)
        }
    }
}
