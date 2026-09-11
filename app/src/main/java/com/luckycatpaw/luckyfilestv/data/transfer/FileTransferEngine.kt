package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.util.DirectorySync
import com.luckycatpaw.luckyfilestv.util.safeAdd
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    val unreadableDirectories: List<String> = emptyList(),
    val copiedEntries: List<CopiedEntry> = emptyList()
)

/** One copy, including whether its source must be recorded for a later move cleanup. */
internal data class CopyRequest(
    val source: TransferSource,
    val target: TransferTarget,
    val replace: Boolean,
    val totalBytes: Long,
    val trackSource: Boolean = false
)

/**
 * Moving bytes, whichever way round the two sides are.
 *
 * An interface because the coordinator above it decides things worth testing that only show
 * up around a transfer that went wrong — the order of copy, record and delete, and a failure
 * on item three not taking four and five with it. [FileTransferEngine] is the one that does
 * the work; a test stands in for it to arrange the failures.
 */
internal interface TransferEngine {

    suspend fun copy(request: CopyRequest, onBytesCopied: suspend (Long) -> Unit): TransferItemResult

    /** @return `null` when the storage cannot rename and the caller has to copy instead. */
    suspend fun tryFastMove(source: TransferSource, target: SourcePath, replace: Boolean): TransferItemResult?

    suspend fun delete(source: TransferSource, copiedEntries: List<CopiedEntry>)
}

internal class FileTransferEngine(
    context: Context,
    private val fileTreeWalker: FileTreeWalker,
    private val sources: FileSourceRegistry,
    private val messages: TransferMessages = AndroidTransferMessages(context)
) : TransferEngine {

    private val appContext = context.applicationContext
    private val replacementTransactionStore = ReplacementTransactionStore(
        context = appContext,
        fileTreeWalker = fileTreeWalker
    )

    override suspend fun copy(request: CopyRequest, onBytesCopied: suspend (Long) -> Unit): TransferItemResult {
        val source = request.source
        val target = request.target
        val state = CopyState(request.totalBytes, onBytesCopied, request.trackSource)

        // The planner normally catches this. Check again before a remote replacement can
        // delete the source through a different spelling of its host or share name.
        if (
            target is TransferTarget.Remote &&
            source.location.transferIdentity() == target.path.transferIdentity()
        ) {
            throw IOException("Source and destination refer to the same entry: ${source.pathValue}")
        }

        // A remote replacement deletes first. Refuse an ancestor before it can take the
        // source with it; the same guard keeps a local replacement from consuming its source.
        if (request.replace && source.location.isSameOrChildEntryOf(SourcePath.parse(target.pathValue))) {
            throw IOException(messages.unsafeFileTree())
        }

        val cleanupWarning = if (request.replace) {
            copyReplacing(
                source = source,
                target = target,
                state = state
            )
        } else {
            copyTo(
                source = source,
                target = target,
                state = state
            )
            false
        }

        return TransferItemResult(
            cleanupWarning = cleanupWarning,
            bytesTransferred = request.totalBytes,
            unreadableDirectories = state.unreadableDirectories,
            copiedEntries = state.copiedEntries.orEmpty()
        )
    }

    /**
     * Moves without copying, where the source can do it itself.
     *
     * Locally that is `rename(2)`, on a share a server-side rename — a file changing folders
     * then costs no traffic at all. Everything the source refuses (another volume, another
     * share, an occupied target) falls through to the copy path, which reports a conflict
     * with a proper message.
     *
     * A server that is gone or a password that is wrong is not such a refusal. Both arrive
     * as an [IOException] like the rest, and treating them as "rename cannot do this" turned
     * a move into a full byte copy over a connection that had just failed — which then
     * failed again, several minutes later and with a message from the copy path. They are
     * passed on so the user sees what actually happened and can retry deliberately.
     */
    override suspend fun tryFastMove(
        source: TransferSource,
        target: SourcePath,
        replace: Boolean
    ): TransferItemResult? {
        currentCoroutineContext().ensureActive()

        if (replace) return null
        if (source.location.scheme != target.scheme) return null

        try {
            sources.source(target).move(source.location, target)
        } catch (unreachable: SourceException.Unreachable) {
            throw unreachable
        } catch (unauthenticated: SourceException.AuthenticationRequired) {
            throw unauthenticated
        } catch (ignored: IOException) {
            return null
        }

        return TransferItemResult(
            cleanupWarning = false,
            bytesTransferred = 0L
        )
    }

    override suspend fun delete(source: TransferSource, copiedEntries: List<CopiedEntry>) {
        withContext(NonCancellable) {
            source.deleteCopied(copiedEntries)
        }
    }

    private suspend fun copyReplacing(source: TransferSource, target: TransferTarget, state: CopyState): Boolean {
        if (target !is TransferTarget.Local) {
            // No journal and no atomic swap on a share: the old entry has to go before the
            // new one can be written. A transfer interrupted in between leaves the target
            // missing, which is why this path is only taken when the user chose to replace.
            target.deleteTree()

            copyTo(
                source = source,
                target = target,
                state = state
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
                state = state
            )

            // The journal protects the old target only until installation. A skipped
            // directory must leave it in place, even though the readable files were copied.
            state.unreadableDirectories.firstOrNull()?.let { throw FileTreeReadException(File(it)) }

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

    private suspend fun copyTo(source: TransferSource, target: TransferTarget, state: CopyState) {
        val copyBuffer = CopyBuffers.acquire()
        val pass = CopyPass(target, state, copyBuffer)

        try {
            requireDestinationFree(target, "")
            state.onBytesCopied(0L)

            source.walk(
                onEntry = { entry -> copyEntry(source, entry, pass) },
                onDirectoryComplete = { entry ->
                    target.setLastModified(entry.relativePath, entry.lastModified)
                },
                onUnreadableDirectory = { directory -> state.unreadableDirectories += directory }
            )

            state.onBytesCopied(state.totalBytes)
            target.flush()
        } catch (e: Exception) {
            // Cancellation needs the same cleanup as a failed write. Ownership is recorded
            // as soon as the root is created, before copying bytes or reporting progress.
            if (pass.targetOwned) deleteForCleanup(target)
            throw e
        } finally {
            CopyBuffers.release(copyBuffer)
        }
    }

    /** Records an entry only after its destination has been written successfully. */
    private suspend fun copyEntry(source: TransferSource, entry: TransferEntry, pass: CopyPass) {
        // A link reported by the walker is unsupported, not evidence of a source changing.
        if (entry.type == FileTreeEntryType.SYMBOLIC_LINK) error(messages.symbolicLinksNotSupported())

        val copiedEntry = if (pass.state.copiedEntries != null) source.captureEntry(entry) else null

        if (entry.type == FileTreeEntryType.DIRECTORY) {
            copyDirectory(entry, pass)
        } else {
            copyFile(entry, pass)
        }

        if (copiedEntry != null) {
            source.requireUnchanged(copiedEntry)
            pass.state.copiedEntries?.add(copiedEntry)
        }
    }

    private suspend fun copyDirectory(entry: TransferEntry, pass: CopyPass) {
        requireDestinationFree(pass.target, entry.relativePath)

        runCatching {
            pass.target.createDirectory(entry.relativePath)
        }.getOrElse {
            throw IllegalStateException(
                appContext.getString(
                    R.string.folder_named_create_failed,
                    destinationName(pass.target, entry.relativePath)
                ),
                it
            )
        }

        pass.createdEntry(entry.relativePath)
    }

    private suspend fun copyFile(entry: TransferEntry, pass: CopyPass) {
        requireDestinationFree(pass.target, entry.relativePath)

        entry.openInput().use { input ->
            val output = BufferedOutputStream(pass.target.openOutput(entry.relativePath))
            pass.createdEntry(entry.relativePath)

            output.use { openOutput -> copyBytes(input, openOutput, pass) }
        }

        pass.target.setLastModified(entry.relativePath, entry.lastModified)
    }

    /** Stream ownership stays with copyFile, so a failed read or callback still closes both ends. */
    private suspend fun copyBytes(input: InputStream, output: OutputStream, pass: CopyPass) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(pass.buffer)
            if (read < 0) break

            output.write(pass.buffer, 0, read)
            pass.recordBytes(read)
        }

        output.flush()
    }

    private suspend fun requireDestinationFree(target: TransferTarget, relativePath: String) {
        check(!target.exists(relativePath)) {
            appContext.getString(R.string.already_exists, destinationName(target, relativePath))
        }
    }

    private fun destinationName(target: TransferTarget, relativePath: String): String =
        relativePath.substringAfterLast('/').ifEmpty { target.name }

    /** One destination and buffer, including the ownership needed if the walk fails halfway through. */
    private class CopyPass(val target: TransferTarget, val state: CopyState, val buffer: ByteArray) {

        var targetOwned = false
            private set

        private var copiedBytes = 0L
        private var lastUpdateNanos = 0L

        fun createdEntry(relativePath: String) {
            if (relativePath.isEmpty()) targetOwned = true
        }

        suspend fun recordBytes(count: Int) {
            val total = state.totalBytes
            val sum = safeAdd(copiedBytes, count.toLong())
            copiedBytes = if (total > 0L) sum.coerceAtMost(total) else sum

            val now = System.nanoTime()
            if (now - lastUpdateNanos >= PROGRESS_UPDATE_NANOS || copiedBytes >= total) {
                lastUpdateNanos = now
                state.onBytesCopied(copiedBytes)
            }
        }
    }

    /** Shared by the copy and replacement paths, so both record the same outcome. */
    private class CopyState(val totalBytes: Long, val onBytesCopied: suspend (Long) -> Unit, trackSource: Boolean) {
        val unreadableDirectories = mutableListOf<String>()
        val copiedEntries = if (trackSource) mutableListOf<CopiedEntry>() else null
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
