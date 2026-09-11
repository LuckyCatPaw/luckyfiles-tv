package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult

/**
 * What a transfer has done so far.
 *
 * Every exit from a transfer reports the same six things, and a cancelled one has to report
 * them too — the partial result is what tells the user which files did make it across. Kept
 * in one place so the record cannot drift apart between the four ways out, and so adding a
 * counter means adding it once.
 */
internal class TransferTally {

    private val completedPaths = mutableListOf<String>()
    private val issues = mutableListOf<TransferIssue>()

    private var skippedCount = 0
    private var cleanupWarningCount = 0
    private var sourceDeleteWarningCount = 0

    /**
     * Records a target that is fully written.
     *
     * A move records this before it removes the source, so neither cancellation nor a failed
     * cleanup can turn a copy that did complete into an apparent total failure.
     */
    fun completed(path: String) {
        completedPaths += path
    }

    fun failed(sourcePath: String, message: String) {
        issues += TransferIssue(sourcePath = sourcePath, message = message)
    }

    fun skipped() {
        skippedCount++
    }

    /** The target is written, but what the engine left behind could not be removed. */
    fun cleanupWarning() {
        cleanupWarningCount++
    }

    /** A move left its source behind, either after a partial copy or a failed deletion. */
    fun sourceDeleteWarning() {
        sourceDeleteWarningCount++
    }

    fun result(cancelled: Boolean): TransferResult = TransferResult(
        completedPaths = completedPaths.toList(),
        skippedCount = skippedCount,
        issues = issues.toList(),
        cleanupWarningCount = cleanupWarningCount,
        sourceDeleteWarningCount = sourceDeleteWarningCount,
        cancelled = cancelled
    )
}
