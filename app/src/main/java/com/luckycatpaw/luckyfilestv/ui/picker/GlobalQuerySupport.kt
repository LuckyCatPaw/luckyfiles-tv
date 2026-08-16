package com.luckycatpaw.luckyfilestv.ui.picker

import android.net.Uri
import android.os.CancellationSignal
import androidx.annotation.PluralsRes
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderChildrenResult
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The parts of a global (cross-provider) query that search and recents had in common:
 * resetting the UI state, fanning out across roots, and turning the outcome into a
 * status message. The two handlers keep only what actually differs.
 */

private const val MAX_PARALLEL_PROVIDER_QUERIES = 4

internal data class ProviderFanOutStats(val errors: Int, val timeouts: Int)

/**
 * Clears the browse location and switches the picker into [mode] with a running message.
 */
internal fun PickerContext.beginGlobalQuery(mode: DisplayMode, runningMessage: String, searchQuery: String = "") {
    uiState.update {
        it.copy(
            displayMode = mode,
            currentSearchQuery = searchQuery,
            currentLocalPath = null,
            currentLocalTitle = null,
            currentLocalDirectoryWritable = false,
            currentLocalTreeSelectable = false,
            providerStack = emptyList(),
            pickerItems = emptyList(),
            providerLoading = true,
            providerInfoMessage = runningMessage,
            providerErrorMessage = null
        )
    }
}

/**
 * Queries every root in parallel, capped at [MAX_PARALLEL_PROVIDER_QUERIES] at a time.
 *
 * [onResult] is invoked for each partial result a provider reports, so the UI can fill in
 * while slow providers are still loading. [isCurrent] guards against a query that was
 * superseded while it was in flight.
 */
internal suspend fun PickerContext.fanOutAcrossRoots(
    roots: List<DocumentRootInfo>,
    initialErrors: Int,
    isCurrent: () -> Boolean,
    observedUri: (DocumentRootInfo) -> Uri,
    query: suspend (DocumentRootInfo, CancellationSignal) -> Result<ProviderChildrenResult>,
    onResult: (DocumentRootInfo, ProviderChildrenResult) -> Unit
): ProviderFanOutStats {
    var errors = initialErrors
    var timeouts = 0
    val semaphore = Semaphore(MAX_PARALLEL_PROVIDER_QUERIES)

    coroutineScope {
        roots.map { root ->
            launch {
                semaphore.withPermit {
                    val outcome = providerQueryRunner.queryUntilSettled(
                        observedUri = observedUri(root),
                        query = { signal -> query(root, signal) }
                    ) { result ->
                        if (isCurrent()) onResult(root, result)
                    }

                    if (isCurrent()) {
                        if (outcome.failure != null || outcome.result?.error != null) errors++
                        if (outcome.loadingTimedOut) timeouts++
                    }
                }
            }
        }.joinAll()
    }

    return ProviderFanOutStats(errors = errors, timeouts = timeouts)
}

/**
 * Message shown while a provider is still delivering results.
 */
internal fun PickerContext.partialResultMessage(result: ProviderChildrenResult, runningMessage: String): String =
    result.info
        ?: if (result.loading) getString(R.string.providers_still_loading) else runningMessage

/**
 * Turns the fan-out outcome into the final status line and clears the loading flag.
 */
internal fun PickerContext.finishGlobalQuery(
    stats: ProviderFanOutStats,
    emptyMessage: String,
    @PluralsRes errorPluralRes: Int
) {
    uiState.update {
        it.copy(
            providerLoading = false,
            providerInfoMessage = when {
                stats.errors > 0 -> getQuantityString(errorPluralRes, stats.errors, stats.errors)
                it.pickerItems.isEmpty() -> emptyMessage
                stats.timeouts > 0 -> getString(R.string.providers_still_loading)
                else -> null
            }
        )
    }
}

/**
 * Early exit when no document providers are reachable at all.
 */
internal fun PickerContext.finishWithoutProviders(emptyMessage: String) {
    uiState.update {
        it.copy(
            providerLoading = false,
            providerInfoMessage = if (it.pickerItems.isEmpty()) emptyMessage else null
        )
    }
}
