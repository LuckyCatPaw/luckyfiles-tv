package com.luckycatpaw.luckyfilestv.ui.main.model

import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflict
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferProgress
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult

internal typealias TransferMode = TransferOperation
internal typealias TransferConflictAnswer = TransferConflictDecision

/**
 * The conflict prompt needs no UI-specific fields, so it is the data-layer type under a
 * name that reads correctly at the call site. Re-declaring it as its own data class used
 * to mean copying three identical fields on every state update.
 */
internal typealias TransferConflictRequest = TransferConflict

/** [details] verbatim from the transfer engine; only the localized [title] is added here. */
internal data class TransferUiProgress(val title: String, val details: TransferProgress)

internal data class TransferCompletion(
    val result: TransferResult,
    val operation: TransferOperation,
    val focusPath: String?
)
