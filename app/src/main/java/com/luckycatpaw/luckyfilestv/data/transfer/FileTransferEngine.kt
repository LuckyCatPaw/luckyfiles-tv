package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.util.DirectorySync
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
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

internal class FileTransferEngine(
    context: Context,
    private val fileTreeWalker: FileTreeWalker,
    private val sources: FileSourceRegistry
) {

    private val appContext = context.applicationContext
    private val replacementTransactionStore = ReplacementTransactionStore(
        context = appContext,
        fileTreeWalker = fileTreeWalker
    )

    suspend fun copy(
        source: TransferSource,
        target: TransferTarget,
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

    /**
     * Moves without copying, where the source can do it itself.
     *
     * Locally that is `rename(2)`, on a share a server-side rename — a file changing folders
     * then costs no traffic at all. Everything the source refuses (another volume, another
     * share, an occupied target) falls through to the copy path, which reports a conflict
     * with a proper message.
     */
    suspend fun tryFastMove(source: TransferSource, target: SourcePath, replace: Boolean): TransferItemResult? {
        currentCoroutineContext().ensureActive()

        if (replace) return null
        if (source.location.scheme != target.scheme) return null

        try {
            sources.source(target).move(source.location, target)
        } catch (ignored: IOException) {
            return null
        }

        return TransferItemResult(
            cleanupWarning = false,
            bytesTransferred = 0L
        )
    }

    suspend fun delete(source: TransferSource) {
        withContext(NonCancellable) {
            source.delete()
        }
    }

    private suspend fun copyReplacing(
        source: TransferSource,
        target: TransferTarget,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit,
        unreadableDirectories: MutableList<String>
    ): Boolean {
        if (target !is TransferTarget.Local) {
            // No journal and no atomic swap on a share: the old entry has to go before the
            // new one can be written. A transfer interrupted in between leaves the target
            // missing, which is why this path is only taken when the user chose to replace.
            target.deleteTree()

            copyTo(
                source = source,
                target = target,
                totalBytes = totalBytes,
                onBytesCopied = onBytesCopied,
                unreadableDirectories = unreadableDirectories
            )

            return false
        }

        val parent = target.file.parentFile
            ?: error(appContext.getString(R.string.target_folder_parent_missing))

        val temporary = createTemporaryDestination(parent)
        val preparation = replacementTransactionStore.prepareReplacement(
            target = target.file,
            preparedReplacement = temporary
        )

        try {
            copyTo(
                source = source,
                target = TransferTarget.Local(temporary),
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
        source: TransferSource,
        target: TransferTarget,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit,
        unreadableDirectories: MutableList<String>
    ) {
        var copiedBytes = 0L
        var lastUpdateNanos = 0L
        var targetOwned = false
        val copyBuffer = CopyBuffers.acquire()

        try {
            check(!target.exists()) {
                appContext.getString(R.string.already_exists, target.name)
            }

            onBytesCopied(0L)

            source.walk(
                onEntry = { entry ->
                    val destinationName = entry.relativePath.substringAfterLast('/').ifEmpty { target.name }

                    when (entry.type) {
                        FileTreeEntryType.DIRECTORY -> {
                            check(!target.exists(entry.relativePath)) {
                                appContext.getString(R.string.already_exists, destinationName)
                            }

                            runCatching {
                                target.createDirectory(entry.relativePath)
                            }.getOrElse {
                                throw IllegalStateException(
                                    appContext.getString(
                                        R.string.folder_named_create_failed,
                                        destinationName
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
                            check(!target.exists(entry.relativePath)) {
                                appContext.getString(R.string.already_exists, destinationName)
                            }

                            entry.openInput().use { input ->
                                val output = BufferedOutputStream(target.openOutput(entry.relativePath))

                                if (entry.relativePath.isEmpty()) {
                                    targetOwned = true
                                }

                                output.use { openOutput ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()

                                        val read = input.read(copyBuffer)

                                        if (read < 0) break

                                        openOutput.write(copyBuffer, 0, read)
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

                                    openOutput.flush()
                                }
                            }

                            target.setLastModified(entry.relativePath, entry.lastModified)
                        }
                    }
                },
                onDirectoryComplete = { entry ->
                    target.setLastModified(entry.relativePath, entry.lastModified)
                },
                onUnreadableDirectory = { directory ->
                    unreadableDirectories += directory
                }
            )

            onBytesCopied(totalBytes)
            target.flush()
        } catch (e: Exception) {
            // Cancellation lands here too — it is an Exception — and wants the same thing: a
            // half written target is not something to leave behind, whether the copy failed
            // or the user stopped it.
            if (targetOwned) {
                deleteForCleanup(target)
            }
            throw e
        } finally {
            CopyBuffers.release(copyBuffer)
        }
    }

    private fun syncDirectory(directory: File?) {
        DirectorySync.sync(directory)
    }

    private suspend fun deleteForCleanup(target: TransferTarget): Boolean = withContext(NonCancellable) {
        runCatching {
            if (target.exists()) target.deleteTree()
        }.isSuccess
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

    /**
     * Hands out the buffer a copy reads through, one item at a time.
     *
     * A megabyte is the right size for one large file and a lot of garbage for five hundred
     * small ones, because a buffer used to be allocated per item. Items are copied in
     * sequence, so a single buffer serves the whole run; the lock is there for the case of
     * two transfers overlapping, and next to opening and closing a file it costs nothing.
     *
     * Held softly so an idle app gives the megabyte back under memory pressure. Losing it
     * costs one allocation on the next copy.
     */
    private object CopyBuffers {

        private const val SIZE = 1024 * 1024

        private var pooled: SoftReference<ByteArray>? = null

        fun acquire(): ByteArray = synchronized(this) {
            pooled?.get()?.also { pooled = null }
        } ?: ByteArray(SIZE)

        fun release(buffer: ByteArray) {
            synchronized(this) { pooled = SoftReference(buffer) }
        }
    }

    companion object {
        private const val PROGRESS_UPDATE_NANOS = 100_000_000L
    }
}
