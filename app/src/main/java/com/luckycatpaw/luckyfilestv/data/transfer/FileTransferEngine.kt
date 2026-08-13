package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal data class TransferItemResult(
    val cleanupWarning: Boolean,
    val bytesTransferred: Long,
    val sourceDeleteFailure: Throwable? = null
)

internal class FileTransferEngine(
    context: Context,
    private val fileTreeWalker: FileTreeWalker
) {

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
        val cleanupWarning = if (replace) {
            copyReplacing(
                source = source,
                target = target,
                totalBytes = totalBytes,
                onBytesCopied = onBytesCopied
            )
        } else {
            copyTo(
                source = source,
                target = target,
                totalBytes = totalBytes,
                onBytesCopied = onBytesCopied
            )
            false
        }

        return TransferItemResult(
            cleanupWarning = cleanupWarning,
            bytesTransferred = totalBytes
        )
    }

    suspend fun tryFastMove(
        source: File,
        target: File,
        replace: Boolean
    ): TransferItemResult? {
        currentCoroutineContext().ensureActive()

        if (replace) return null

        if (source.renameTo(target)) {
            return TransferItemResult(
                cleanupWarning = false,
                bytesTransferred = 0L
            )
        }

        return null
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
        onBytesCopied: suspend (Long) -> Unit
    ): Boolean {
        val parent = target.parentFile
            ?: error(appContext.getString(R.string.target_folder_parent_missing))

        val temporary = createTemporaryDestination(parent)

        try {
            copyTo(
                source = source,
                target = temporary,
                totalBytes = totalBytes,
                onBytesCopied = onBytesCopied
            )

            currentCoroutineContext().ensureActive()

            return withContext(NonCancellable) {
                replacementTransactionStore.installReplacement(
                    target = target,
                    preparedReplacement = temporary
                )
            }
        } finally {
            deleteForCleanup(temporary)
        }
    }

    private suspend fun copyTo(
        source: File,
        target: File,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit
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
                                val outputStream = Files.newOutputStream(
                                    destination.toPath(),
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE
                                )

                                if (entry.relativePath.isEmpty()) {
                                    targetOwned = true
                                }

                                BufferedOutputStream(outputStream).use { output ->
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
                                }
                            }

                            destination.setLastModified(entry.file.lastModified())
                        }
                    }
                },
                onDirectoryComplete = { entry ->
                    destinationFor(target, entry.relativePath)
                        .setLastModified(entry.file.lastModified())
                }
            )

            onBytesCopied(totalBytes)
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

    private suspend fun deleteForCleanup(file: File): Boolean {
        return withContext(NonCancellable) {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                true
            } else {
                runCatching {
                    fileTreeWalker.delete(file)
                }.isSuccess
            }
        }
    }

    private fun destinationFor(targetRoot: File, relativePath: String): File {
        return if (relativePath.isEmpty()) {
            targetRoot
        } else {
            File(targetRoot, relativePath)
        }
    }

    private fun createTemporaryDestination(parent: File): File {
        var candidate: File

        do {
            candidate = File(parent, ".luckyfiles-${UUID.randomUUID()}")
        } while (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS))

        return candidate
    }

    private fun safeAdd(first: Long, second: Long): Long {
        return if (Long.MAX_VALUE - first < second) {
            Long.MAX_VALUE
        } else {
            first + second
        }
    }

    private fun safeProgressAdd(
        current: Long,
        addition: Long,
        total: Long
    ): Long {
        val result = safeAdd(current, addition)
        return if (total > 0L) result.coerceAtMost(total) else result
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_UPDATE_NANOS = 100_000_000L
    }
}
