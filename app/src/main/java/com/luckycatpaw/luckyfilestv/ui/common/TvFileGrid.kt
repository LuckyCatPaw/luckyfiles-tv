package com.luckycatpaw.luckyfilestv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun <T> TvFileGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    gridState: LazyGridState,
    focusState: TvFileGridFocusState,
    columnCount: Int,
    selectedIndex: Int,
    selectionVisible: Boolean,
    onSelectionChanged: (index: Int, item: T) -> Unit,
    onExitUp: () -> Unit,
    onItemClick: (T) -> Unit,
    onItemLongClick: (T) -> Unit,
    onItemFocused: (index: Int, item: T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isMarked: (T) -> Boolean = { false },
    itemContent: @Composable (
        item: T,
        selected: Boolean,
        onClick: () -> Unit,
        onFocused: () -> Unit,
        modifier: Modifier
    ) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentItems by rememberUpdatedState(items)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentSelectionVisible by rememberUpdatedState(selectionVisible)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnItemLongClick by rememberUpdatedState(onItemLongClick)
    val emptyFocusRequester = remember { FocusRequester() }

    val itemHeightPx = remember(density) {
        with(density) { TvFileGridDefaults.ItemHeight.toPx() }
    }

    val interaction = remember { GridInteraction() }

    fun cancelActivation() {
        interaction.activationJob?.cancel()
        interaction.activationJob = null
        interaction.longPressTriggered = false
    }

    fun cancelNavigation() {
        interaction.navigationJob?.cancel()
        interaction.navigationJob = null
        interaction.pendingNavigationIndex = -1
        focusState.cancelPendingFocus()
    }

    /**
     * Scrolls the target into view and declares it as the focus target.
     */
    fun startNavigation(targetIndex: Int, isRepeat: Boolean = false) {
        val oldIndex = selectedIndex
        val rowChanged = (targetIndex / columnCount) != (oldIndex / columnCount)

        interaction.pendingNavigationIndex = targetIndex
        interaction.navigationJob?.cancel()

        val layoutInfo = gridState.layoutInfo
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset

        if (viewportHeight > 0) {
            val scrollResult = calculateInstantScroll(
                gridState = gridState,
                index = targetIndex,
                viewportHeight = viewportHeight,
                itemHeightPx = itemHeightPx,
                rowChanged = rowChanged
            )

            if (!scrollResult.needed) {
                if (focusState.focusIndex(targetIndex)) {
                    return
                }
            }
        }

        interaction.navigationJob = scope.launch {
            val target = interaction.pendingNavigationIndex
            if (target !in currentItems.indices) return@launch

            val currentViewportHeight = gridState.layoutInfo.viewportEndOffset -
                gridState.layoutInfo.viewportStartOffset

            if (currentViewportHeight <= 0) {
                gridState.scrollToItem(target)
            } else {
                val scrollResult = calculateInstantScroll(
                    gridState = gridState,
                    index = target,
                    viewportHeight = currentViewportHeight,
                    itemHeightPx = itemHeightPx,
                    rowChanged = rowChanged
                )

                if (scrollResult.needed) {
                    if (isRepeat) {
                        gridState.scrollToItem(scrollResult.index, scrollResult.offset)
                    } else {
                        // Animation and focus in parallel so focus doesn't lag behind the scroll
                        launch {
                            gridState.animateScrollToItem(scrollResult.index, scrollResult.offset)
                        }
                    }
                }
            }

            focusState.focusIndex(target)
        }
    }

    DisposableEffect(items, enabled, focusState, columnCount) {
        if (!enabled) {
            cancelActivation()
            cancelNavigation()
        }

        onDispose {
            cancelActivation()
            cancelNavigation()
        }
    }

    if (items.isEmpty()) {
        // The placeholder is composed here but placed on the next layout pass, so a
        // single attempt can be too early. requestFocus also throws while no node is
        // attached, hence the guard around every attempt rather than just the first.
        LaunchedEffect(enabled, focusState) {
            if (enabled) {
                repeat(EMPTY_STATE_PLACEMENT_FRAMES) {
                    val granted = runCatching {
                        emptyFocusRequester.requestFocus(FocusDirection.Enter)
                    }.getOrDefault(false)

                    if (granted) {
                        return@LaunchedEffect
                    }

                    withFrameNanos { }
                }
            }
        }

        Box(
            modifier = modifier
                .focusRequester(emptyFocusRequester)
                .focusable(enabled = enabled)
                .onPreviewKeyEvent { event ->
                    if (
                        enabled &&
                        event.type == KeyEventType.KeyDown &&
                        event.nativeKeyEvent.keyCode ==
                        AndroidKeyEvent.KEYCODE_DPAD_UP
                    ) {
                        onExitUp()
                        true
                    } else {
                        false
                    }
                }
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        state = gridState,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (!enabled) {
                    cancelActivation()
                    return@onPreviewKeyEvent true
                }

                val keyCode = event.nativeKeyEvent.keyCode
                val isDirectionKey = keyCode in DirectionKeyCodes
                val isActivationKey = keyCode in ActivationKeyCodes

                if (isActivationKey) {
                    if (event.type == KeyEventType.KeyDown) {
                        if (
                            event.nativeKeyEvent.repeatCount == 0 &&
                            interaction.activationJob?.isActive != true
                        ) {
                            interaction.longPressTriggered = false
                            interaction.activationJob = scope.launch {
                                delay(LONG_PRESS_DELAY)

                                if (
                                    isActive &&
                                    selectedIndex == currentSelectedIndex &&
                                    selectedIndex in currentItems.indices
                                ) {
                                    interaction.longPressTriggered = true
                                    currentOnItemLongClick(
                                        currentItems[selectedIndex]
                                    )
                                }
                            }
                        }

                        return@onPreviewKeyEvent true
                    }

                    interaction.activationJob?.cancel()
                    interaction.activationJob = null

                    if (
                        !interaction.longPressTriggered &&
                        selectedIndex in items.indices
                    ) {
                        onItemClick(items[selectedIndex])
                    }

                    interaction.longPressTriggered = false
                    return@onPreviewKeyEvent true
                }

                if (!isDirectionKey) {
                    return@onPreviewKeyEvent false
                }

                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent true
                }

                cancelActivation()

                if (
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                    selectedIndex in 0 until columnCount
                ) {
                    cancelNavigation()
                    onExitUp()
                    return@onPreviewKeyEvent true
                }

                val nextIndex = targetIndex(
                    currentIndex = selectedIndex,
                    itemCount = items.size,
                    columnCount = columnCount,
                    keyCode = keyCode
                )

                if (nextIndex != null) {
                    onSelectionChanged(nextIndex, items[nextIndex])
                    startNavigation(nextIndex, isRepeat = event.nativeKeyEvent.repeatCount > 0)
                }

                true
            },
        horizontalArrangement = Arrangement.spacedBy(
            TvFileGridDefaults.HorizontalSpacing
        ),
        verticalArrangement = Arrangement.spacedBy(
            TvFileGridDefaults.VerticalSpacing
        )
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> itemKey(item) }
        ) { index, item ->
            val requester = focusState.requesterAt(index)

            DisposableEffect(itemKey(item), index, requester) {
                focusState.register(index, requester)

                onDispose {
                    focusState.release(index, requester)
                }
            }

            // Optimization: Only observe focus changes for this specific index.
            // Using derivedStateOf prevents every item from waking up when focus moves elsewhere.
            val isTargeted by remember(index) {
                androidx.compose.runtime.derivedStateOf { focusState.requestedIndex == index }
            }
            LaunchedEffect(isTargeted, enabled) {
                if (isTargeted && enabled) {
                    focusState.claim(index)
                }
            }

            // Optimization: Prevent recomposing the whole item when selectedIndex changes for other items.
            val isSelected by remember(index) {
                androidx.compose.runtime.derivedStateOf {
                    currentEnabled && currentSelectionVisible && index == currentSelectedIndex
                }
            }

            // Optimization: Stabilize lambdas to prevent unnecessary recompositions of itemContent.
            val onClick = remember(item, enabled, onItemClick) {
                { if (enabled) onItemClick(item) }
            }
            val onFocused = remember(index, item, enabled, onItemFocused) {
                { if (enabled) onItemFocused(index, item) }
            }

            Box {
                itemContent(
                    item,
                    isSelected,
                    onClick,
                    onFocused,
                    // Deliberately no `canFocus = enabled`.
                    //
                    // Every overlay is a Dialog in its own window, so while one is up this
                    // grid cannot receive input at all — the key handler above swallows
                    // whatever might still arrive. Taking focusability away from the item
                    // that currently holds focus bought nothing and cost the scroll
                    // position: Compose has to re-target focus the moment its node stops
                    // being focusable, and the node it settles on is pulled into view.
                    //
                    // Left focusable, the item simply keeps focus behind the dialog and has
                    // it back when the dialog closes. The highlight still disappears in the
                    // meantime, because `isSelected` below stays gated on `enabled`.
                    Modifier.focusRequester(requester)
                )

                if (isMarked(item)) {
                    SelectionBadge(
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(8.dp)
            .size(28.dp)
            .background(
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(14.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✓",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun targetIndex(currentIndex: Int, itemCount: Int, columnCount: Int, keyCode: Int): Int? {
    if (currentIndex !in 0 until itemCount) {
        return null
    }

    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT ->
            (currentIndex - 1).takeIf {
                currentIndex % columnCount != 0
            }

        AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
            (currentIndex + 1).takeIf {
                currentIndex % columnCount != columnCount - 1 && it < itemCount
            }

        AndroidKeyEvent.KEYCODE_DPAD_UP ->
            (currentIndex - columnCount).takeIf { it >= 0 }

        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
            val sameColumnBelow = currentIndex + columnCount

            when {
                sameColumnBelow < itemCount -> sameColumnBelow

                // The last row is incomplete and does not reach this column. Fall back to its
                // last item: without this the key press is swallowed and the user has to go
                // up and left to reach entries that are visibly one row below.
                currentIndex / columnCount < (itemCount - 1) / columnCount -> itemCount - 1

                else -> null
            }
        }

        else -> null
    }
}

/**
 * Bookkeeping for key handling, held across recompositions by a single [remember].
 *
 * Deliberately plain `var`s rather than `mutableStateOf`: none of these are read while
 * composing, they only feed the key handler and the coroutines it starts. As snapshot state
 * every D-pad press would write two or three of them and recompose the whole grid, including
 * the `LazyVerticalGrid` call, without a single pixel changing as a result.
 */
private class GridInteraction {
    var activationJob: Job? = null
    var navigationJob: Job? = null
    var pendingNavigationIndex: Int = -1
    var longPressTriggered: Boolean = false
}

private data class ScrollResult(val index: Int, val offset: Int, val needed: Boolean)

private fun calculateInstantScroll(
    gridState: LazyGridState,
    index: Int,
    viewportHeight: Int,
    itemHeightPx: Float,
    rowChanged: Boolean
): ScrollResult {
    val layoutInfo = gridState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo

    if (visibleItems.isEmpty()) {
        return ScrollResult(index, 0, false)
    }

    val target = visibleItems.firstOrNull { it.index == index }

    if (target != null) {
        val top = target.offset.y
        val bottom = top + target.size.height

        // Only scroll if actually off-screen vertically
        if (!rowChanged) {
            return ScrollResult(index, 0, false)
        }

        return when {
            top < 0 ->
                ScrollResult(index, 0, true)

            bottom > viewportHeight ->
                ScrollResult(index, -(viewportHeight - target.size.height), true)

            else -> ScrollResult(index, 0, false)
        }
    }

    // Off-screen: Just jump so it's visible at the edge
    val currentFirstItem = visibleItems.minByOrNull { it.index } ?: return ScrollResult(index, 0, false)

    return if (index < currentFirstItem.index) {
        ScrollResult(index, 0, true)
    } else {
        ScrollResult(index, -(viewportHeight - itemHeightPx.toInt()), true)
    }
}

private val LONG_PRESS_DELAY = 550.milliseconds

/** Frames the empty-directory placeholder may spend waiting to be placed. */
private const val EMPTY_STATE_PLACEMENT_FRAMES = 3
