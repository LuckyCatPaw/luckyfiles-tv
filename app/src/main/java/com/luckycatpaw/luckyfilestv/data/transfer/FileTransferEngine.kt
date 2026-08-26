package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.util.DirectorySync
import com.luckycatpaw.luckyfilestv.util.FileUtil
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class TransferItemResult(
    val cleanupWarning: Boolean,
    val bytesTransferred: Long,
    val sourceDeleteFailure: Throwable? = null,
    val unreadableDirectories: List<String> = emptyList()
)

internal class FileTransferEngine(context: Context, private val fileTreeWalker: FileTreeWalker) {

    private val appContext = context.applicationContext
    private val replacementTransactionStore = ReplacementTransactionStore(
        context = appContext,
        fileTreeWalker = fileTreeWalker
    )

    suspend fun copy(
        source: File,
        target: File,
        replace: Boolean,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit
    ): TransferItemResult {
        val unreadableDirectories = mutableListOf<String>()

        val cleanupWarning = if (replace) {
            copyReplacing(
                source = source,
                target = target,
                totalBytes = totalBytes,
                onBytesCopied = onBytesCopied,
                unreadableDirectories = unreadableDirectories
            )
        } else {
            copyTo(
                source = source,
                target = target,
                totalBytes = totalBytes,
                onBytesCopied = onBytesCopied,
                unreadableDirectories = unreadableDirectories
            )
            false
        }

        return TransferItemResult(
            cleanupWarning = cleanupWarning,
            bytesTransferred = totalBytes,
            unreadableDirectories = unreadableDirectories
        )
    }

    suspend fun tryFastMove(source: File, target: File, replace: Boolean): TransferItemResult? {
        currentCoroutineContext().ensureActive()

        if (replace) return null

        try {
            FileUtil.moveWithoutReplacing(source, target)
        } catch (ignored: IOException) {
            // Occupied target, different volume or any other rename(2) failure: fall back to the
            // copy path, which reports an occupied target with a proper message.
            return null
        }

        return TransferItemResult(
            cleanupWarning = false,
            bytesTransferred = 0L
        )
    }

    suspend fun delete(file: File) {
        withContext(NonCancellable) {
            fileTreeWalker.delete(file)
        }
    }

    private suspend fun copyReplacing(
        source: File,
        target: File,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit,
        unreadableDirectories: MutableList<String>
    ): Boolean {
        val parent = target.parentFile
            ?: error(appContext.getString(R.string.target_folder_parent_missing))

        val temporary = createTemporaryDestination(parent)
        val preparation = replacementTransactionStore.prepareReplacement(
            target = target,
            preparedReplacement = temporary
        )

        try {
            copyTo(
                source = source,
                target = temporary,
                totalBytes = totalBytes,
                onBytesCopied = onBytesCopied,
                unreadableDirectories = unreadableDirectories
            )

            currentCoroutineContext().ensureActive()

            return withContext(NonCancellable) {
                val cleanupWarning = replacementTransactionStore.installReplacement(preparation)
                syncDirectory(parent)
                cleanupWarning
            }
        } finally {
            withContext(NonCancellable) {
                replacementTransactionStore.finishPreparation(preparation)
            }
        }
    }

    private suspend fun copyTo(
        source: File,
        target: File,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit,
        unreadableDirectories: MutableList<String>
    ) {
        var copiedBytes = 0L
        var lastUpdateNanos = 0L
        var targetOwned = false
        val copyBuffer = ByteArray(COPY_BUFFER_SIZE)

        try {
            check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                appContext.getString(R.string.already_exists, target.name)
            }

            onBytesCopied(0L)

            fileTreeWalker.walk(
                root = source,
                onEntry = { entry ->
                    val destination = destinationFor(target, entry.relativePath)

                    when (entry.type) {
                        FileTreeEntryType.DIRECTORY -> {
                            check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                appContext.getString(R.string.already_exists, destination.name)
                            }

                            runCatching {
                                Files.createDirectory(destination.toPath())
                            }.getOrElse {
                                throw IllegalStateException(
                                    appContext.getString(
                                        R.string.folder_named_create_failed,
                                        destination.name
                                    ),
                                    it
                                )
                            }

                            if (entry.relativePath.isEmpty()) {
                                targetOwned = true
                            }
                        }

                        FileTreeEntryType.SYMBOLIC_LINK -> {
                            error(appContext.getString(R.string.symbolic_links_not_supported))
                        }

                        FileTreeEntryType.FILE -> {
                            check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                appContext.getString(R.string.already_exists, destination.name)
                            }

                            val parent = destination.parentFile

                            if (parent == null || !parent.isDirectory) {
                                error(appContext.getString(R.string.target_folder_create_failed))
                            }

                            FileInputStream(entry.file).use { input ->
                                val channel = FileChannel.open(
                                    destination.toPath(),
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE
                                )

                                if (entry.relativePath.isEmpty()) {
                                    targetOwned = true
                                }

                                channel.use { openChannel ->
                                    val output = BufferedOutputStream(
                                        Channels.newOutputStream(openChannel)
                                    )

                                    while (true) {
                                        currentCoroutineContext().ensureActive()

                                        val read = input.read(copyBuffer)

                                        if (read < 0) break

                                        output.write(copyBuffer, 0, read)
                                        copiedBytes = safeProgressAdd(
                                            current = copiedBytes,
                                            addition = read.toLong(),
                                            total = totalBytes
                                        )

                                        val now = System.nanoTime()

                                        if (
                                            now - lastUpdateNanos >= PROGRESS_UPDATE_NANOS ||
                                            copiedBytes >= totalBytes
                                        ) {
                                            lastUpdateNanos = now
                                            onBytesCopied(copiedBytes)
                                        }
                                    }

                                    output.flush()
                                    openChannel.force(true)
                                }
                            }

                            destination.setLastModified(entry.file.lastModified())
                        }
                    }
                },
                onDirectoryComplete = { entry ->
                    destinationFor(target, entry.relativePath)
                        .setLastModified(entry.file.lastModified())
                },
                onUnreadableDirectory = { directory ->
                    unreadableDirectories += directory.absolutePath
                }
            )

            onBytesCopied(totalBytes)
            syncDirectory(target.parentFile)
        } catch (e: CancellationException) {
            if (targetOwned) {
                deleteForCleanup(target)
            }
            throw e
        } catch (e: Exception) {
            if (targetOwned) {
                deleteForCleanup(target)
            }
            throw e
        }
    }

    private fun syncDirectory(directory: File?) {
        DirectorySync.sync(directory)
    }

    private suspend fun deleteForCleanup(file: File): Boolean = withContext(NonCancellable) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            true
        } else {
            runCatching {
                fileTreeWalker.delete(file)
            }.isSuccess
        }
    }

    private fun destinationFor(targetRoot: File, relativePath: String): File = if (relativePath.isEmpty()) {
        targetRoot
    } else {
        File(targetRoot, relativePath)
    }

    private fun createTemporaryDestination(parent: File): File {
        var candidate: File

        do {
            candidate = File(parent, ".luckyfiles-${UUID.randomUUID()}")
        } while (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS))

        return candidate
    }

    private fun safeAdd(first: Long, second: Long): Long = if (Long.MAX_VALUE - first < second) {
        Long.MAX_VALUE
    } else {
        first + second
    }

    private fun safeProgressAdd(current: Long, addition: Long, total: Long): Long {
        val result = safeAdd(current, addition)
        return if (total > 0L) result.coerceAtMost(total) else result
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_UPDATE_NANOS = 100_000_000L
    }
}
