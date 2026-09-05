package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class BrowserFocusArea {
    GRID,
    HEADER
}

/**
 * Which part of the browser owns focus, and which grid entry is current.
 *
 * The class no longer tracks *which* header button was last focused: the header row
 * is a focus group with `Modifier.focusRestorer()`, so re-entering it lands on the
 * button the user left from. Requesting the group is enough.
 */
@Stable
internal class BrowserFocusState<K> internal constructor(
    private var keys: List<K>,
    private val gridFocusState: TvFileGridFocusState,
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    initialFocusIndex: Int
) {
    var selectedIndex by mutableIntStateOf(initialFocusIndex)
    var focusedIndex by mutableIntStateOf(initialFocusIndex)
    var activeFocusArea by mutableStateOf(BrowserFocusArea.GRID)
    var gridSelectionVisible by mutableStateOf(value = true)

    fun updateKeys(newKeys: List<K>) {
        if (newKeys == keys) return

        val oldKey = selectedIndex.let { if (it in keys.indices) keys[it] else null }
        keys = newKeys

        if (oldKey != null) {
            val newIndex = newKeys.indexOf(oldKey)
            if (newIndex >= 0) {
                selectedIndex = newIndex
                if (focusedIndex >= 0) focusedIndex = newIndex
            } else {
                selectedIndex = selectedIndex.coerceAtLeast(0).coerceAtMost(newKeys.lastIndex)
                if (focusedIndex >= 0) focusedIndex = selectedIndex
            }
        } else if (selectedIndex < 0 && newKeys.isNotEmpty()) {
            // Keep it at -1 if it was -1, unless we want a default.
            // For now, staying at -1 is safer for initial focus logic.
        } else {
            selectedIndex = selectedIndex.coerceAtLeast(-1).coerceAtMost(newKeys.lastIndex)
            if (focusedIndex >= 0) focusedIndex = selectedIndex
        }
    }

    /**
     * Index whose focus event is expected next, used to ignore focus callbacks from
     * items that are merely passing through while the grid settles.
     */
    var expectedGridIndex by mutableIntStateOf(initialFocusIndex)

    /**
     * Whether the header holds focus because the user asked for it (D-pad up out of
     * the grid) rather than because nothing else took it.
     *
     * Without this distinction the two cases are indistinguishable — a header button's
     * onFocusChanged fires either way — and an accidental focus would be restored
     * faithfully on every later run of the initial focus effect.
     */
    var headerFocusIsExplicit by mutableStateOf(value = false)
        private set

    private var focusRestoreJob: Job? = null

    fun onGridFocused(index: Int) {
        if ((expectedGridIndex >= 0) && (index != expectedGridIndex)) return
        expectedGridIndex = -1
        focusedIndex = index
        selectedIndex = index
        activeFocusArea = BrowserFocusArea.GRID
        gridSelectionVisible = true
        headerFocusIsExplicit = false
    }

    fun onSelectionChanged(index: Int) {
        expectedGridIndex = -1
        selectedIndex = index
        activeFocusArea = BrowserFocusArea.GRID
        gridSelectionVisible = true
        headerFocusIsExplicit = false
    }

    fun onHeaderFocused() {
        activeFocusArea = BrowserFocusArea.HEADER
        gridSelectionVisible = false
    }

    /** [headerRequester] addresses the header group, not an individual button. */
    fun onExitUp(headerRequester: FocusRequester?) {
        cancelActiveJobs()
        gridFocusState.cancelPendingFocus()
        gridSelectionVisible = false
        // Only here is the header a deliberate destination.
        headerFocusIsExplicit = true
        headerRequester?.let { requester ->
            runCatching { requester.requestFocus() }
        }
    }

    /**
     * Moves focus from the header back into the grid, e.g. on D-pad down.
     *
     * Falls back to the first entry when no index is remembered. Without that the header is
     * a dead end whenever the grid was never focused or its index was reset — pressing down
     * simply did nothing, with no way out short of leaving the directory.
     */
    fun restoreGridFocus(enabled: Boolean = true) {
        if (!enabled) return

        val index = when {
            selectedIndex in gridFocusState.indices -> selectedIndex
            focusedIndex in gridFocusState.indices -> focusedIndex
            gridFocusState.indices.isEmpty() -> -1
            else -> 0
        }

        if (index < 0) return

        activeFocusArea = BrowserFocusArea.GRID
        gridSelectionVisible = true
        selectedIndex = index
        headerFocusIsExplicit = false

        // Optimization: Try immediate focus to beat the native D-pad movement if
        // the item is already visible.
        if (gridFocusState.focusIndex(index)) {
            focusedIndex = index
            cancelActiveJobs()
            return
        }

        focusRestoreJob?.cancel()
        focusRestoreJob = scope.launch {
            val visible = gridState.layoutInfo.visibleItemsInfo.any {
                it.index == index
            }

            if (!visible) {
                gridState.scrollToItem(index)
            }

            // Declare and be done. If the row is still composing, the item picks the
            // request up on its own.
            if (gridFocusState.focusIndex(index)) {
                focusedIndex = index
            }
        }
    }

    private fun cancelActiveJobs() {
        focusRestoreJob?.cancel()
        focusRestoreJob = null
    }

    /**
     * @param explicitRequest `true` when the screen asked for this focus itself, e.g. after
     *   creating a folder. Such a request outranks keeping focus in the header: the action
     *   was started there, but its result lives in the grid.
     */
    internal suspend fun applyInitialFocus(
        targetIndex: Int,
        enabled: Boolean,
        explicitRequest: Boolean,
        onItemFocused: (Int) -> Unit,
        headerRequester: () -> FocusRequester?
    ) {
        cancelActiveJobs()
        gridFocusState.cancelPendingFocus()

        if (!enabled) {
            gridSelectionVisible = false
            return
        }

        if (keys.isEmpty()) {
            expectedGridIndex = -1
            selectedIndex = -1
            focusedIndex = -1
            gridSelectionVisible = false
            activeFocusArea = BrowserFocusArea.GRID
            headerFocusIsExplicit = false
            return
        }

        // Return to the header only when the user put focus there on purpose and nothing
        // else was asked for.
        if (activeFocusArea == BrowserFocusArea.HEADER && headerFocusIsExplicit && !explicitRequest) {
            val requester = headerRequester()
            if (requester != null) {
                gridSelectionVisible = false
                withFrameNanos { }
                runCatching { requester.requestFocus() }
                return
            }
        }

        if (targetIndex in gridFocusState.indices) {
            val targetVisible = gridState.layoutInfo.visibleItemsInfo.any {
                it.index == targetIndex
            }

            if (!targetVisible) {
                // Suspends until the scroll has been applied, which also pulls the
                // target into composition.
                gridState.scrollToItem(targetIndex)
            }

            selectedIndex = targetIndex
            focusedIndex = targetIndex
            activeFocusArea = BrowserFocusArea.GRID
            gridSelectionVisible = true
            headerFocusIsExplicit = false
            expectedGridIndex = targetIndex

            onItemFocused(targetIndex)

            if (gridFocusState.focusIndex(targetIndex)) {
                expectedGridIndex = -1
            }

            // No header fallback. The request is latched; the item serves it when it
            // composes. Parking focus in the header meanwhile is what made the header
            // sticky in the first place.
            return
        }

        selectedIndex = -1
        focusedIndex = -1
        gridSelectionVisible = false
        expectedGridIndex = -1

        val requester = headerRequester()
        if (requester != null) {
            activeFocusArea = BrowserFocusArea.HEADER
            // Not explicit. This is the fallback for "the grid had nowhere to put focus",
            // which is the very case the flag exists to tell apart from a deliberate D-pad
            // up. Claiming it here made a later run — once the grid does have a target —
            // hand focus straight back to the header.
            headerFocusIsExplicit = false
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
}

@Composable
internal fun <K> rememberBrowserFocusState(
    keys: List<K>,
    gridFocusState: TvFileGridFocusState,
    gridState: LazyGridState,
    scope: CoroutineScope,
    initialFocusIndex: Int,
    key: Any? = Unit
): BrowserFocusState<K> {
    val state = remember(key) {
        BrowserFocusState(
            keys = keys,
            gridFocusState = gridFocusState,
            gridState = gridState,
            scope = scope,
            initialFocusIndex = initialFocusIndex
        )
    }

    androidx.compose.runtime.SideEffect {
        state.updateKeys(keys)
    }

    return state
}
