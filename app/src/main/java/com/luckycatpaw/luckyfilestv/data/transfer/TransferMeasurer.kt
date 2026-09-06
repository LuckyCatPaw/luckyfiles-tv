package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.util.safeAdd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The plan with the sizes filled in. */
internal data class Measurement(val items: List<PlannedTransfer>, val totalBytes: Long)

/**
 * Reads how big a transfer will be before it starts.
 *
 * The number exists for the progress bar, which is why it is read once here rather than
 * while the copy runs: a total that grows during a transfer makes the bar move backwards.
 * An item that cannot be measured is marked invalid instead of stopping the rest — one
 * unreadable folder should not cancel a selection of twenty.
 *
 * @param willRenameInPlace whether an item is expected to move without copying and needs
 *   no measuring. Injected because answering it means asking the source registry.
 * @param describeFailure turns whatever a scan threw into a sentence for the user.
 */
internal class TransferMeasurer(
    private val messages: TransferMessages,
    private val willRenameInPlace: suspend (PlannedTransfer) -> Boolean,
    private val describeFailure: (Throwable) -> String
) {

    suspend fun measure(items: List<PlannedTransfer>, operation: TransferOperation, tally: TransferTally): Measurement {
        val measured = mutableListOf<PlannedTransfer>()
        var totalBytes = 0L

        for (item in items) {
            currentCoroutineContext().ensureActive()

            val sized = size(item, operation, tally)

            measured += sized
            totalBytes = safeAdd(totalBytes, sized.size ?: 0L)
        }

        return Measurement(items = measured, totalBytes = totalBytes)
    }

    private suspend fun size(
        item: PlannedTransfer,
        operation: TransferOperation,
        tally: TransferTally
    ): PlannedTransfer {
        // A move inside one volume or one share is a rename: no bytes travel, and walking
        // the tree for a number nothing displays is what made moving a large folder feel
        // like it had stalled. Everything that will have to copy is measured here like a
        // copy, which is what gives the progress a total that does not grow later.
        if (operation == TransferOperation.MOVE && willRenameInPlace(item)) return item

        val scan = try {
            item.source.scan()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tally.failed(item.source.pathValue, describeFailure(failure))
            return item.copy(invalid = true)
        }

        if (scan.symbolicLinkCount > 0L) {
            tally.failed(item.source.pathValue, messages.symbolicLinksNotSupported())
            return item.copy(invalid = true)
        }

        return item.copy(size = scan.size)
    }
}
