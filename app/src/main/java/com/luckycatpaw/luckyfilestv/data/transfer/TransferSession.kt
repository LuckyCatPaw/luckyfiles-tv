package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferCancelledException
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflict
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferProgress
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TransferSessionCompletion(
    val result: TransferResult,
    val operation: TransferOperation,
    val targetPath: String
)

internal data class TransferSessionState(
    val running: Boolean = false,
    val operation: TransferOperation? = null,
    val targetPath: String? = null,
    val progress: TransferProgress? = null,
    val conflict: TransferConflict? = null,
    val completion: TransferSessionCompletion? = null
)

internal object TransferSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(TransferSessionState())
    val state: StateFlow<TransferSessionState> = _state.asStateFlow()

    private var job: Job? = null

    private val conflictAnswers = Channel<TransferConflictDecision>(Channel.CONFLATED)

    val isRunning: Boolean
        get() = _state.value.running

    fun start(
        context: Context,
        sourcePaths: List<String>,
        targetDirectoryPath: String,
        operation: TransferOperation
    ): Boolean {
        if (job?.isActive == true) return false
        if (sourcePaths.isEmpty()) return false

        val appContext = context.applicationContext
        val coordinator = TransferCoordinator(appContext)

        drainConflictAnswers()

        _state.value = TransferSessionState(
            running = true,
            operation = operation,
            targetPath = targetDirectoryPath,
            progress = null,
            conflict = null,
            completion = null
        )

        FileTransferService.start(appContext)

        job = scope.launch {
            val result = try {
                coordinator.execute(
                    sourcePaths = sourcePaths,
                    targetDirectoryPath = targetDirectoryPath,
                    operation = operation,
                    onConflict = { conflict -> awaitConflictDecision(conflict) },
                    onProgress = { progress ->
                        _state.update { it.copy(progress = progress) }
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
                            sourcePath = targetDirectoryPath,
                            message = e.message ?: appContext.getString(R.string.error_generic)
                        )
                    ),
                    cleanupWarningCount = 0,
                    sourceDeleteWarningCount = 0,
                    cancelled = false
                )
            } finally {
                drainConflictAnswers()
            }

            _state.update {
                it.copy(
                    running = false,
                    progress = null,
                    conflict = null,
                    completion = TransferSessionCompletion(
                        result = result,
                        operation = operation,
                        targetPath = targetDirectoryPath
                    )
                )
            }
            job = null
        }

        return true
    }

    fun cancel() {
        job?.cancel()
    }

    fun answerConflict(decision: TransferConflictDecision) {
        conflictAnswers.trySend(decision)
    }

    fun consumeCompletion() {
        _state.update { it.copy(completion = null) }
    }

    private suspend fun awaitConflictDecision(conflict: TransferConflict): TransferConflictDecision {
        drainConflictAnswers()

        _state.update { it.copy(conflict = conflict, progress = null) }

        return try {
            conflictAnswers.receive()
        } finally {
            _state.update { it.copy(conflict = null) }
        }
    }

    private fun drainConflictAnswers() {
        while (conflictAnswers.tryReceive().isSuccess) {
        }
    }
}
