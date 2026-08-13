package com.luckycatpaw.luckyfilestv.ui.main

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.transfer.TransferCoordinator
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferCancelledException
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiState
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferCompletion
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferConflictAnswer
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferConflictRequest
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferMode
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferUiProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TransferManager(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val uiState: MutableStateFlow<MainUiState>,
    private val transferCoordinator: TransferCoordinator,
    private val onTransferFinished: (String, String?) -> Unit
) {
    private var transferJob: Job? = null

    fun prepareTransfer(mode: TransferMode, sources: List<BrowserItem>) {
        if (sources.isEmpty() || transferJob?.isActive == true) return
        uiState.update { it.copy(
            transferSources = sources,
            transferMode = mode,
            focusTargetPath = sources.firstOrNull()?.path
        )}
    }

    fun cancelPreparedTransfer() {
        if (transferJob?.isActive == true) return
        uiState.update { it.copy(
            transferSources = emptyList(),
            transferMode = null
        )}
    }

    fun startPreparedTransfer(targetPath: String) {
        val mode = uiState.value.transferMode ?: return
        val sources = uiState.value.transferSources
        if (sources.isEmpty() || (transferJob?.isActive == true)) return

        uiState.update { it.copy(transferCompletion = null) }
        transferJob = modelScope.launch {
            val result = try {
                transferCoordinator.execute(
                    sourcePaths = sources.map { it.path },
                    targetDirectoryPath = targetPath,
                    operation = mode,
                    onConflict = { conflict ->
                        withContext(Dispatchers.Main.immediate) {
                            uiState.update { it.copy(transferProgress = null) }
                            requestTransferConflict(
                                sourceName = conflict.sourceName,
                                targetDirectory = conflict.targetDirectory,
                                multipleItems = conflict.multipleItems
                            )
                        }
                    },
                    onProgress = { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            uiState.update { it.copy(
                                transferProgress = TransferUiProgress(
                                    title = appContext.getString(
                                        if (mode == TransferMode.COPY) R.string.copying else R.string.moving
                                    ),
                                    currentItem = progress.currentItem,
                                    totalItems = progress.totalItems,
                                    currentName = progress.currentName,
                                    bytesProcessed = progress.bytesProcessed,
                                    totalBytes = progress.totalBytes,
                                    bytesPerSecond = progress.bytesPerSecond
                                )
                            )}
                        }
                    }
                )
            } catch (e: TransferCancelledException) {
                e.partialResult
            } catch (e: CancellationException) {
                TransferResult(
                    completedPaths = emptyList(),
                    skippedCount = 0,
                    issues = emptyList(),
                    cleanupWarningCount = 0,
                    sourceDeleteWarningCount = 0,
                    cancelled = true
                )
            } catch (e: Exception) {
                TransferResult(
                    completedPaths = emptyList(),
                    skippedCount = 0,
                    issues = listOf(
                        TransferIssue(
                            sourcePath = targetPath,
                            message = e.message ?: appContext.getString(R.string.error_generic)
                        )
                    ),
                    cleanupWarningCount = 0,
                    sourceDeleteWarningCount = 0,
                    cancelled = false
                )
            } finally {
                uiState.value.conflictRequest?.deferred?.let { deferred ->
                    if (!deferred.isCompleted) deferred.cancel()
                }
                uiState.update { it.copy(
                    conflictRequest = null,
                    transferProgress = null
                )}
                transferJob = null
            }

            val resultFocusPath = result.completedPaths.lastOrNull()
            uiState.update { it.copy(
                transferSources = emptyList(),
                transferMode = null,
                transferCompletion = TransferCompletion(
                    result = result,
                    operation = mode,
                    focusPath = resultFocusPath
                )
            )}
            onTransferFinished(targetPath, resultFocusPath)
        }
    }

    fun cancelRunningTransfer() {
        transferJob?.cancel()
    }

    fun answerTransferConflict(answer: TransferConflictAnswer) {
        uiState.value.conflictRequest?.deferred?.complete(answer)
    }

    fun consumeTransferCompletion() {
        uiState.update { it.copy(transferCompletion = null) }
    }

    private suspend fun requestTransferConflict(
        sourceName: String,
        targetDirectory: String,
        multipleItems: Boolean
    ): TransferConflictAnswer {
        val deferred = CompletableDeferred<TransferConflictAnswer>()

        uiState.update { it.copy(
            conflictRequest = TransferConflictRequest(
                sourceName = sourceName,
                targetDirectory = targetDirectory,
                multipleItems = multipleItems,
                deferred = deferred
            )
        )}

        return try {
            deferred.await()
        } finally {
            uiState.update { it.copy(conflictRequest = null) }
        }
    }
}
