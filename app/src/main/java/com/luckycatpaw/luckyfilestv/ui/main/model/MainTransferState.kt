package com.luckycatpaw.luckyfilestv.ui.main.model

import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult
import kotlinx.coroutines.CompletableDeferred

internal typealias TransferMode = TransferOperation
internal typealias TransferConflictAnswer = TransferConflictDecision

internal data class TransferUiProgress(
    val title: String,
    val currentItem: Int,
    val totalItems: Int,
    val currentName: String,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long?
)

internal data class TransferConflictRequest(
    val sourceName: String,
    val targetDirectory: String,
    val multipleItems: Boolean,
    val deferred: CompletableDeferred<TransferConflictAnswer>
)

internal data class TransferCompletion(
    val result: TransferResult,
    val operation: TransferOperation,
    val focusPath: String?
)
