package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import android.os.storage.StorageManager
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeCycleException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeOutsideRootException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.source.AndroidSourceMessages
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferCancelledException
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflict
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferProgress
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult
import com.luckycatpaw.luckyfilestv.util.safeAdd
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class TransferCoordinator(
    context: Context,
    private val fileTreeWalker: FileTreeWalker = FileTreeWalker(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val sources: FileSourceRegistry by lazy {
        FileSourceRegistry.create(appContext, fileTreeWalker = fileTreeWalker)
    }

    private val appContext = context.applicationContext
    private val sourceMessages = AndroidSourceMessages(appContext)
    private val messages: TransferMessages = AndroidTransferMessages(appContext)
    private val planner = TransferPlanner(messages = messages, targetExists = ::targetExists)
    private val measurer = TransferMeasurer(
        messages = messages,
        willRenameInPlace = ::willRenameInPlace,
        describeFailure = ::readableMessage
    )
    private val transferEngine by lazy {
        FileTransferEngine(
            context = appContext,
            fileTreeWalker = fileTreeWalker,
            sources = sources
        )
    }

    suspend fun execute(
        sourcePaths: List<String>,
        targetDirectoryPath: String,
        operation: TransferOperation,
        onConflict: suspend (TransferConflict) -> TransferConflictDecision,
        onProgress: suspend (TransferProgress) -> Unit
    ): TransferResult = withContext(ioDispatcher) {
        val tally = TransferTally()

        try {
            val targetLocation = SourcePath.parse(targetDirectoryPath)
            val targetDirectory = if (targetLocation.isLocal) requireDirectory(targetDirectoryPath) else null

            val plan = planner.plan(
                request = PlanRequest(
                    sources = sourcePaths.map(::transferSourceFor),
                    targetLocation = targetLocation,
                    localTargetDirectory = targetDirectory,
                    operation = operation
                ),
                tally = tally,
                onConflict = onConflict
            )

            if (plan.cancelled) return@withContext tally.result(cancelled = true)

            val measurement = measurer.measure(plan.items, operation, tally)
            val plannedItems = measurement.items
            var totalBytes = measurement.totalBytes

            val spaceIssue = targetDirectory?.let {
                insufficientSpaceIssue(targetDirectory = it, requiredBytes = totalBytes)
            }

            if (spaceIssue != null) {
                tally.failed(spaceIssue.sourcePath, spaceIssue.message)

                return@withContext tally.result(cancelled = false)
            }

            val run = Run(
                items = plannedItems.filterNot { it.invalid },
                operation = operation,
                totalBytes = totalBytes,
                targetDirectory = targetDirectory,
                tally = tally,
                onProgress = onProgress
            )

            for ((index, item) in run.items.withIndex()) {
                currentCoroutineContext().ensureActive()
                transferOne(item, index, run)
            }

            tally.result(cancelled = false)
        } catch (e: CancellationException) {
            throw TransferCancelledException(partialResult = tally.result(cancelled = true), cause = e)
        }
    }

    /** What one pass over the planned items carries along. */
    private class Run(
        val items: List<PlannedTransfer>,
        val operation: TransferOperation,
        totalBytes: Long,
        val targetDirectory: File?,
        val tally: TransferTally,
        val onProgress: suspend (TransferProgress) -> Unit
    ) {
        val startedNanos: Long = System.nanoTime()

        var processedBytes = 0L
        var transferredBytes = 0L

        /**
         * Grows while the pass runs: a move that expected to rename and could not joins the
         * total late, which is one step on the bar instead of one per item.
         */
        var totalBytes = totalBytes
    }

    /**
     * Transfers one item, or records why it could not be.
     *
     * A failure here is this item's failure. The pass carries on, because nineteen files
     * that went across are worth more to the user than a clean abort at the second one.
     */
    private suspend fun transferOne(item: PlannedTransfer, index: Int, run: Run) {
        // Reads the surrounding state every time it runs rather than capturing it, which is
        // what lets the same lambda serve the announcement before an item and the
        // byte-by-byte updates during it.
        var itemSize = item.size ?: 0L
        val reportProgress: suspend (Long) -> Unit = { copied ->
            run.onProgress(
                TransferProgress(
                    currentItem = index + 1,
                    totalItems = run.items.size,
                    currentName = item.source.name,
                    bytesProcessed = safeAdd(run.processedBytes, copied),
                    totalBytes = run.totalBytes,
                    bytesPerSecond = speed(run, copied)
                )
            )
        }

        reportProgress(0L)

        try {
            val result = when (run.operation) {
                TransferOperation.COPY -> copy(item, itemSize, reportProgress)

                TransferOperation.MOVE -> {
                    val moved = move(item, run, reportProgress)
                    itemSize = moved.size
                    moved.result
                }
            }

            if (!result.completionRecorded) run.tally.completed(item.target.value)

            result.value.unreadableDirectories.forEach { path ->
                run.tally.failed(path, messages.unreadableSkipped(File(path).name))
            }

            run.processedBytes = safeAdd(run.processedBytes, itemSize)
            run.transferredBytes = safeAdd(run.transferredBytes, result.value.bytesTransferred)

            if (result.value.sourceDeleteFailure != null) run.tally.sourceDeleteWarning()
            if (result.value.cleanupWarning) run.tally.cleanupWarning()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            run.processedBytes = safeAdd(run.processedBytes, itemSize)
            run.tally.failed(item.source.pathValue, readableMessage(failure))
        }
    }

    private suspend fun copy(
        item: PlannedTransfer,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit
    ): ItemOutcome = ItemOutcome(
        value = transferEngine.copy(
            source = item.source,
            target = transferTargetFor(item.target),
            replace = item.replace,
            totalBytes = totalBytes,
            onBytesCopied = onBytesCopied
        ),
        completionRecorded = false
    )

    /**
     * A move, by rename where the storage allows it and by copy and delete where it does not.
     *
     * The fallback measures late, because the planner skipped anything it expected to
     * rename, and checks the room again for the same reason: neither number was needed
     * while the rename was still the plan.
     */
    private suspend fun move(item: PlannedTransfer, run: Run, onBytesCopied: suspend (Long) -> Unit): MoveOutcome {
        val renamed = transferEngine.tryFastMove(
            source = item.source,
            target = item.target,
            replace = item.replace
        )

        if (renamed != null) {
            return MoveOutcome(ItemOutcome(renamed, completionRecorded = false), item.size ?: 0L)
        }

        if (item.source.isSymbolicLink()) error(messages.symbolicLinksNotSupported())

        val itemSize = item.size ?: measureLate(item, run)

        run.targetDirectory?.let { directory ->
            insufficientSpaceIssue(directory, itemSize)?.let { error(it.message) }
        }

        val copied = transferEngine.copy(
            source = item.source,
            target = transferTargetFor(item.target),
            replace = item.replace,
            totalBytes = itemSize,
            onBytesCopied = onBytesCopied
        )

        // The target is complete at this point. Recorded before the source is removed, so
        // neither cancellation nor a failed cleanup can turn a copy that did go through
        // into an apparent total failure.
        run.tally.completed(item.target.value)

        return MoveOutcome(
            result = ItemOutcome(
                value = TransferItemResult(
                    cleanupWarning = copied.cleanupWarning,
                    bytesTransferred = itemSize,
                    sourceDeleteFailure = deleteSource(item),
                    unreadableDirectories = copied.unreadableDirectories
                ),
                completionRecorded = true
            ),
            size = itemSize
        )
    }

    /**
     * Removes what was just copied, and hands back why it could not be.
     *
     * A source left behind is a warning, not a failure: the copy went through, and telling
     * the user the move failed would send them looking for files that are already there.
     */
    private suspend fun deleteSource(item: PlannedTransfer): Throwable? = try {
        transferEngine.delete(item.source)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure
    }

    /**
     * The size of a source that changed its mind between planning and moving.
     *
     * It joins the total here rather than in the measuring pass, which is one step on the
     * progress bar instead of one per item.
     */
    private suspend fun measureLate(item: PlannedTransfer, run: Run): Long {
        val scan = item.source.scan()

        if (scan.symbolicLinkCount > 0L) error(messages.symbolicLinksNotSupported())

        run.totalBytes = safeAdd(run.totalBytes, scan.size)

        return scan.size
    }

    /** Only shown for a copy: a rename moves no bytes, so a rate would be meaningless. */
    private fun speed(run: Run, copied: Long): Long? {
        val elapsedNanos = System.nanoTime() - run.startedNanos
        val transferred = safeAdd(run.transferredBytes, copied)

        if (run.operation != TransferOperation.COPY || elapsedNanos <= 0L || transferred <= 0L) return null

        return (transferred.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble()).toLong()
    }

    private class ItemOutcome(val value: TransferItemResult, val completionRecorded: Boolean)

    private class MoveOutcome(val result: ItemOutcome, val size: Long)

    /**
     * Whether this item is expected to move without copying, and therefore needs no scan.
     *
     * The two guards in front repeat what [FileTransferEngine.tryFastMove] checks before it
     * even asks the source, so that a prediction and the attempt behind it cannot disagree
     * about the obvious cases. A wrong `true` is not harmful: the fallback in the transfer
     * loop still measures the tree, it just does so late.
     */
    private suspend fun willRenameInPlace(item: PlannedTransfer): Boolean {
        if (item.replace) return false
        if (item.source.location.scheme != item.target.scheme) return false

        return try {
            sources.source(item.target).canMoveWithoutCopy(item.source.location, item.target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun transferTargetFor(path: SourcePath): TransferTarget = if (path.isLocal) {
        TransferTarget.Local(File(path.value))
    } else {
        TransferTarget.Remote(path = path, sources = sources)
    }

    private suspend fun targetExists(path: SourcePath): Boolean = if (path.isLocal) {
        Files.exists(File(path.value).toPath(), LinkOption.NOFOLLOW_LINKS)
    } else {
        runCatching { sources.source(path).stat(path) != null }.getOrDefault(false)
    }

    private fun transferSourceFor(path: String): TransferSource {
        val location = SourcePath.parseOrNull(path)

        return if (location == null || location.isLocal) {
            TransferSource.Local(
                file = File(path).toPath().toAbsolutePath().normalize().toFile(),
                fileTreeWalker = fileTreeWalker
            )
        } else {
            TransferSource.Remote(path = location, sources = sources)
        }
    }

    private fun getAvailableBytes(file: File): Long? {
        val storageManager = appContext.getSystemService(StorageManager::class.java)
        return runCatching {
            val uuid = storageManager.getUuidForPath(file)
            storageManager.getAllocatableBytes(uuid)
        }.getOrElse {
            runCatching { file.usableSpace }.getOrNull()
        }
    }

    private fun insufficientSpaceIssue(targetDirectory: File, requiredBytes: Long): TransferIssue? {
        if (requiredBytes <= 0L) return null

        // usableSpace returns 0 for a full volume as well as for a failed statfs(2), so the two
        // cases are told apart via totalSpace: it is only 0 when the measurement itself is
        // unavailable. A genuine "0 bytes free" has to fail the check instead of skipping it.
        val volumeBytes = runCatching { targetDirectory.totalSpace }.getOrNull() ?: return null
        if (volumeBytes <= 0L) return null

        val usableBytes = getAvailableBytes(targetDirectory) ?: return null

        val requiredWithMargin = safeAdd(requiredBytes, FREE_SPACE_MARGIN_BYTES)
        if (usableBytes >= requiredWithMargin) return null

        return TransferIssue(
            sourcePath = targetDirectory.absolutePath,
            message = messages.notEnoughSpace(requiredBytes = requiredBytes, availableBytes = usableBytes)
        )
    }

    private fun requireDirectory(path: String): File {
        val directory = File(path).canonicalFile

        require(directory.exists() && directory.isDirectory) { messages.targetFolderMissing() }

        require(directory.canWrite()) { messages.targetReadOnly() }

        return directory
    }

    private fun readableMessage(error: Throwable): String = when (error) {
        is FileTreeReadException -> messages.folderReadFailed(error.directory.name)

        is FileTreeCycleException,
        is FileTreeOutsideRootException ->
            messages.unsafeFileTree()

        // A source phrases its failures for the log: "Access denied during WRITE:
        // smb://nas/media". SourceMessages is the single place that turns one into a
        // sentence, and it reads the operation off the exception itself — the one passed
        // here only covers the shapes that carry none.
        is SourceException ->
            sourceMessages.localize(error, SourceOperation.WRITE)

        // Everything the transfer layer raises itself is already localised, e.g. the
        // "already exists" the engine throws when a target appeared underneath it.
        else ->
            error.message
                ?.takeIf { it.isNotBlank() }
                ?: messages.generic()
    }

    private companion object {
        const val FREE_SPACE_MARGIN_BYTES = 8L * 1024L * 1024L
    }
}
