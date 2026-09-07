package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferProgress
import com.luckycatpaw.luckyfilestv.util.safeAdd
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What to transfer, how much of it there is, and where it is going. */
internal data class RunRequest(
    val items: List<PlannedTransfer>,
    val operation: TransferOperation,
    val totalBytes: Long,
    /** The target as a directory, `null` when it is on a share. There is no room to check. */
    val targetDirectory: File?
)

/**
 * Carries out a plan.
 *
 * What is worth being sure about here only shows up when something goes wrong: a source that
 * cannot be deleted after it was copied is a warning and not a failure, a storage that will
 * not rename has to fall back to copying, and an item that fails in the middle must not take
 * the ones after it with it.
 *
 * @param targetFor turns a planned location into somewhere writable.
 * @param describeFailure turns whatever went wrong into a sentence for the user.
 */
internal class TransferRunner(
    private val engine: TransferEngine,
    private val messages: TransferMessages,
    private val freeSpace: FreeSpaceCheck,
    private val targetFor: (SourcePath) -> TransferTarget,
    private val describeFailure: (Throwable) -> String
) {

    suspend fun run(request: RunRequest, tally: TransferTally, onProgress: suspend (TransferProgress) -> Unit) {
        val pass = Run(request = request, tally = tally, onProgress = onProgress)

        for ((index, item) in request.items.withIndex()) {
            currentCoroutineContext().ensureActive()
            transferOne(item, index, pass)
        }
    }

    /** What one pass over the planned items carries along. */
    private class Run(
        val request: RunRequest,
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
        var totalBytes = request.totalBytes
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
                    totalItems = run.request.items.size,
                    currentName = item.source.name,
                    bytesProcessed = safeAdd(run.processedBytes, copied),
                    totalBytes = run.totalBytes,
                    bytesPerSecond = speed(run, copied)
                )
            )
        }

        reportProgress(0L)

        try {
            val result = when (run.request.operation) {
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
            run.tally.failed(item.source.pathValue, describeFailure(failure))
        }
    }

    private suspend fun copy(
        item: PlannedTransfer,
        totalBytes: Long,
        onBytesCopied: suspend (Long) -> Unit
    ): ItemOutcome = ItemOutcome(
        value = engine.copy(
            source = item.source,
            target = targetFor(item.target),
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
        val renamed = engine.tryFastMove(
            source = item.source,
            target = item.target,
            replace = item.replace
        )

        if (renamed != null) {
            return MoveOutcome(ItemOutcome(renamed, completionRecorded = false), item.size ?: 0L)
        }

        if (item.source.isSymbolicLink()) error(messages.symbolicLinksNotSupported())

        val itemSize = item.size ?: measureLate(item, run)

        run.request.targetDirectory?.let { directory ->
            freeSpace.issueFor(directory, itemSize)?.let { error(it.message) }
        }

        val copied = engine.copy(
            source = item.source,
            target = targetFor(item.target),
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
        engine.delete(item.source)
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

        if (run.request.operation != TransferOperation.COPY || elapsedNanos <= 0L || transferred <= 0L) return null

        return (transferred.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble()).toLong()
    }

    private class ItemOutcome(val value: TransferItemResult, val completionRecorded: Boolean)

    private class MoveOutcome(val result: ItemOutcome, val size: Long)
}
