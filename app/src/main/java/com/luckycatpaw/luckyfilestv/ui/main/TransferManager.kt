package com.luckycatpaw.luckyfilestv.ui.main

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.transfer.TransferSession
import com.luckycatpaw.luckyfilestv.data.transfer.TransferSessionCompletion
import com.luckycatpaw.luckyfilestv.data.transfer.TransferSessionState
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiState
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferCompletion
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferConflictAnswer
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferMode
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferUiProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class TransferManager(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val uiState: MutableStateFlow<MainUiState>,
    private val onTransferFinished: (String, String?) -> Unit,
    private val onNotificationPermissionNeeded: () -> Unit = {}
) {
    private var handledCompletion: TransferSessionCompletion? = null

    init {
        modelScope.launch {
            TransferSession.state.collect(::applySessionState)
        }
    }

    fun prepareTransfer(mode: TransferMode, sources: List<BrowserItem>) {
        if (sources.isEmpty() || TransferSession.isRunning) return
        uiState.update {
            it.copy(
                transferSources = sources,
                transferMode = mode,
                focusTargetPath = sources.firstOrNull()?.path
            )
        }
    }

    fun cancelPreparedTransfer() {
        if (TransferSession.isRunning) return
        uiState.update {
            it.copy(
                transferSources = emptyList(),
                transferMode = null
            )
        }
    }

    fun startPreparedTransfer(targetPath: String) {
        val mode = uiState.value.transferMode ?: return
        val sources = uiState.value.transferSources
        if (sources.isEmpty()) return

        uiState.update { it.copy(transferCompletion = null) }

        onNotificationPermissionNeeded()

        val started = TransferSession.start(
            context = appContext,
            sourcePaths = sources.map { it.path },
            targetDirectoryPath = targetPath,
            operation = mode
        )

        if (started) {
            handledCompletion = null
        }
    }

    fun cancelRunningTransfer() {
        TransferSession.cancel()
    }

    fun answerTransferConflict(answer: TransferConflictAnswer) {
        TransferSession.answerConflict(answer)
    }

    fun consumeTransferCompletion() {
        uiState.update { it.copy(transferCompletion = null) }
        TransferSession.consumeCompletion()
    }

    private fun applySessionState(session: TransferSessionState) {
        val operation = session.operation

        uiState.update { state ->
            state.copy(
                transferProgress = session.progress?.let { progress ->
                    TransferUiProgress(
                        title = appContext.getString(
                            if (operation == TransferMode.COPY) R.string.copying else R.string.moving
                        ),
                        details = progress
                    )
                },
                conflictRequest = session.conflict
            )
        }

        val completion = session.completion ?: return
        if (completion === handledCompletion) return
        handledCompletion = completion

        val resultFocusPath = completion.result.completedPaths.lastOrNull()

        uiState.update {
            it.copy(
                transferSources = emptyList(),
                transferMode = null,
                transferCompletion = TransferCompletion(
                    result = completion.result,
                    operation = completion.operation,
                    focusPath = resultFocusPath
                )
            )
        }

        onTransferFinished(completion.targetPath, resultFocusPath)
    }
}
