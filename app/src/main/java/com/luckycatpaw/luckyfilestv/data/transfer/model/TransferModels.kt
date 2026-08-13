package com.luckycatpaw.luckyfilestv.data.transfer.model

import kotlinx.coroutines.CancellationException

enum class FileConflictPolicy {
    REPLACE,
    KEEP_BOTH,
    SKIP
}

enum class TransferOperation {
    COPY,
    MOVE
}

data class TransferConflict(
    val sourceName: String,
    val targetDirectory: String,
    val multipleItems: Boolean
)

data class TransferConflictDecision(
    val policy: FileConflictPolicy?,
    val applyToAll: Boolean,
    val cancelled: Boolean
)

data class TransferProgress(
    val currentItem: Int,
    val totalItems: Int,
    val currentName: String,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long?
)

data class TransferIssue(
    val sourcePath: String,
    val message: String
)

data class TransferResult(
    val completedPaths: List<String>,
    val skippedCount: Int,
    val issues: List<TransferIssue>,
    val cleanupWarningCount: Int,
    val sourceDeleteWarningCount: Int,
    val cancelled: Boolean
)

class TransferCancelledException(
    val partialResult: TransferResult,
    cause: CancellationException
) : CancellationException(cause.message) {
    init {
        initCause(cause)
    }
}
