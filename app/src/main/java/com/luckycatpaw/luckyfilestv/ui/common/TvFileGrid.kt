package com.luckycatpaw.luckyfilestv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val currentEnabled by rememberUpdatedState(enabled)
    val currentItems by rememberUpdatedState(items)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnItemLongClick by rememberUpdatedState(onItemLongClick)
    val emptyFocusRequester = remember { FocusRequester() }

    val rowStepPx = with(density) {
        (
                TvFileGridDefaults.ItemHeight +
                        TvFileGridDefaults.VerticalSpacing
                ).toPx()
    }

    var activationJob by remember {
        mutableStateOf<Job?>(null)
    }

    var navigationJob by remember {
        mutableStateOf<Job?>(null)
    }

    var pendingNavigationIndex by remember {
        mutableIntStateOf(-1)
    }

    var longPressTriggered by remember {
        mutableStateOf(false)
    }

    fun cancelActivation() {
        activationJob?.cancel()
        activationJob = null
        longPressTriggered = false
    }

    fun cancelNavigation() {
        navigationJob?.cancel()
        navigationJob = null
        pendingNavigationIndex = -1
    }

    fun startNavigation(targetIndex: Int) {
        pendingNavigationIndex = targetIndex

        if (navigationJob?.isActive == true) {
            return
        }

        navigationJob = scope.launch {
            while (isActive) {
                if (!currentEnabled) {
                    break
                }

                val target = pendingNavigationIndex
                if (target !in currentItems.indices) {
                    break
                }

                val scrollAmount = requiredScrollForIndex(
                    gridState = gridState,
                    index = target,
                    columnCount = columnCount,
                    rowStepPx = rowStepPx
                )

                if (abs(scrollAmount) > 1f) {
                    val rows = (
                            abs(scrollAmount) / rowStepPx
                            ).roundToInt().coerceAtLeast(1)

                    val duration = (
                            82 + (rows - 1) * 22
                            ).coerceIn(82, 155)

                    gridState.animateScrollBy(
                        value = scrollAmount,
                        animationSpec = tween(
                            durationMillis = duration,
                            easing = LinearEasing
                        )
                    )

                    withFrameNanos { }
                }

                if (!currentEnabled || target != pendingNavigationIndex) {
                    continue
                }

                val visible = gridState.layoutInfo.visibleItemsInfo.any {
                    it.index == target
                }

                if (!visible) {
                    gridState.scrollToItem(target)
                    withFrameNanos { }
                }

                if (!currentEnabled || target != pendingNavigationIndex) {
                    continue
                }

                if (focusState.requestFocus(target)) {
                    break
                }

                withFrameNanos { }
            }
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
        LaunchedEffect(enabled, focusState) {
            if (enabled) {
                withFrameNanos { }
                emptyFocusRequester.requestFocus()
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
                val isDirectionKey = keyCode in directionKeyCodes
                val isActivationKey = keyCode in activationKeyCodes

                if (isActivationKey) {
                    if (event.type == KeyEventType.KeyDown) {
                        if (
                            event.nativeKeyEvent.repeatCount == 0 &&
                            activationJob?.isActive != true
                        ) {
                            val pressedIndex = selectedIndex

                            longPressTriggered = false
                            activationJob = scope.launch {
                                delay(LONG_PRESS_DELAY_MILLIS)

                                if (
                                    isActive &&
                                    pressedIndex == currentSelectedIndex &&
                                    pressedIndex in currentItems.indices
                                ) {
                                    longPressTriggered = true
                                    currentOnItemLongClick(
                                        currentItems[pressedIndex]
                                    )
                                }
                            }
                        }

                        return@onPreviewKeyEvent true
                    }

                    activationJob?.cancel()
                    activationJob = null

                    if (
                        !longPressTriggered &&
                        selectedIndex in items.indices
                    ) {
                        onItemClick(items[selectedIndex])
                    }

                    longPressTriggered = false
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
                    startNavigation(nextIndex)
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
                focusState.attach(index, requester)

                onDispose {
                    focusState.detach(index, requester)
                }
            }

            Box {
                itemContent(
                    item,
                    enabled && selectionVisible && index == selectedIndex,
                    {
                        if (enabled) {
                            onItemClick(item)
                        }
                    },
                    {
                        if (enabled) {
                            onItemFocused(index, item)
                        }
                    },
                    Modifier
                        .focusRequester(requester)
                        .focusProperties {
                            canFocus = enabled
                        }
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
private fun SelectionBadge(
    modifier: Modifier = Modifier
) {
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

private fun targetIndex(
    currentIndex: Int,
    itemCount: Int,
    columnCount: Int,
    keyCode: Int
): Int? {
    if (currentIndex !in 0 until itemCount) {
        return null
    }

    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT ->
            (currentIndex - 1).takeIf { it >= 0 }

        AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
            (currentIndex + 1).takeIf { it < itemCount }

        AndroidKeyEvent.KEYCODE_DPAD_UP ->
            (currentIndex - columnCount).takeIf { it >= 0 }

        AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
            (currentIndex + columnCount).takeIf { it < itemCount }

        else -> null
    }
}

private fun requiredScrollForIndex(
    gridState: LazyGridState,
    index: Int,
    columnCount: Int,
    rowStepPx: Float
): Float {
    val layoutInfo = gridState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo

    if (visibleItems.isEmpty()) {
        return 0f
    }

    val target = visibleItems.firstOrNull {
        it.index == index
    }

    if (target != null) {
        val top = target.offset.y
        val bottom = top + target.size.height
        val viewportTop = layoutInfo.viewportStartOffset
        val viewportBottom = layoutInfo.viewportEndOffset

        return when {
            top < viewportTop ->
                (top - viewportTop).toFloat()

            bottom > viewportBottom ->
                (bottom - viewportBottom).toFloat()

            else -> 0f
        }
    }

    val first = visibleItems.minByOrNull {
        it.index
    } ?: return 0f

    val last = visibleItems.maxByOrNull {
        it.index
    } ?: return 0f

    val targetRow = index / columnCount

    return if (index < first.index) {
        val firstRow = first.index / columnCount
        (targetRow - firstRow) * rowStepPx
    } else {
        val lastRow = last.index / columnCount
        (targetRow - lastRow) * rowStepPx
    }
}

private val directionKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_DPAD_UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN
)

private val activationKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A
)

private const val LONG_PRESS_DELAY_MILLIS = 550L
