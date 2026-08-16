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

@Stable
internal class BrowserFocusState<K, H> internal constructor(
    private val keys: List<K>,
    private val gridFocusState: TvFileGridFocusState,
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    initialFocusIndex: Int
) {
    var selectedIndex by mutableIntStateOf(initialFocusIndex)
    var focusedIndex by mutableIntStateOf(initialFocusIndex)
    var activeFocusArea by mutableStateOf(BrowserFocusArea.GRID)
    var gridSelectionVisible by mutableStateOf(value = true)
    var lastHeaderTarget by mutableStateOf<H?>(null)

    var pendingFocusIndex by mutableIntStateOf(initialFocusIndex)

    private var focusRestoreJob: Job? = null

    fun onGridFocused(index: Int) {
        if ((pendingFocusIndex >= 0) && (index != pendingFocusIndex)) return
        pendingFocusIndex = -1
        focusedIndex = index
        selectedIndex = index
        activeFocusArea = BrowserFocusArea.GRID
        gridSelectionVisible = true
    }

    fun onSelectionChanged(index: Int) {
        pendingFocusIndex = -1
        selectedIndex = index
        activeFocusArea = BrowserFocusArea.GRID
        gridSelectionVisible = true
    }

    fun onHeaderFocused(target: H? = null) {
        activeFocusArea = BrowserFocusArea.HEADER
        gridSelectionVisible = false
        target?.let { lastHeaderTarget = it }
    }

    fun onExitUp(headerRequester: FocusRequester?) {
        cancelActiveJobs()
        gridSelectionVisible = false
        headerRequester?.requestFocus()
    }

    fun restoreGridFocus(enabled: Boolean = true) {
        if (!enabled) return

        val index = when {
            selectedIndex in gridFocusState.indices -> selectedIndex
            focusedIndex in gridFocusState.indices -> focusedIndex
            else -> -1
        }

        if (index >= 0) {
            activeFocusArea = BrowserFocusArea.GRID
            gridSelectionVisible = true
            selectedIndex = index

            focusRestoreJob?.cancel()
            focusRestoreJob = scope.launch {
                val visible = gridState.layoutInfo.visibleItemsInfo.any {
                    it.index == index
                }

                if (!visible) {
                    gridState.scrollToItem(index)
                }

                repeat(3) {
                    withFrameNanos { }

                    if (!enabled || selectedIndex != index) {
                        return@launch
                    }

                    if (gridFocusState.requestFocus(index)) {
                        focusedIndex = index
                        return@launch
                    }
                }
            }
        }
    }

    fun cancelActiveJobs() {
        focusRestoreJob?.cancel()
        focusRestoreJob = null
    }

    internal suspend fun applyInitialFocus(
        targetIndex: Int,
        enabled: Boolean,
        onItemFocused: (Int) -> Unit,
        headerRequester: (H?) -> FocusRequester?
    ) {
        cancelActiveJobs()

        if (!enabled) {
            gridSelectionVisible = false
            return
        }

        if (keys.isEmpty()) {
            pendingFocusIndex = -1
            selectedIndex = -1
            focusedIndex = -1
            gridSelectionVisible = false
            activeFocusArea = BrowserFocusArea.GRID
            lastHeaderTarget = null
            return
        }

        if (activeFocusArea == BrowserFocusArea.HEADER) {
            val requester = headerRequester(lastHeaderTarget)
            if (requester != null) {
                gridSelectionVisible = false
                withFrameNanos { }
                requester.requestFocus()
                return
            }
        }

        if (targetIndex in gridFocusState.indices) {
            val targetVisible = gridState.layoutInfo.visibleItemsInfo.any {
                it.index == targetIndex
            }

            if (!targetVisible) {
                gridState.scrollToItem(targetIndex)
                withFrameNanos { }
            }

            selectedIndex = targetIndex
            focusedIndex = targetIndex
            activeFocusArea = BrowserFocusArea.GRID
            gridSelectionVisible = true

            onItemFocused(targetIndex)

            if (gridFocusState.requestFocus(targetIndex)) {
                pendingFocusIndex = -1
            } else {
                withFrameNanos { }
                if (gridFocusState.requestFocus(targetIndex)) {
                    pendingFocusIndex = -1
                }
            }
        } else {
            selectedIndex = -1
            focusedIndex = -1
            gridSelectionVisible = false
            pendingFocusIndex = -1

            val requester = headerRequester(null)
            if (requester != null) {
                activeFocusArea = BrowserFocusArea.HEADER
                withFrameNanos { }
                requester.requestFocus()
            }
        }
    }
}

@Composable
internal fun <K, H> rememberBrowserFocusState(
    keys: List<K>,
    gridFocusState: TvFileGridFocusState,
    gridState: LazyGridState,
    scope: CoroutineScope,
    initialFocusIndex: Int
): BrowserFocusState<K, H> = remember(keys) {
    BrowserFocusState(
        keys = keys,
        gridFocusState = gridFocusState,
        gridState = gridState,
        scope = scope,
        initialFocusIndex = initialFocusIndex
    )
}
