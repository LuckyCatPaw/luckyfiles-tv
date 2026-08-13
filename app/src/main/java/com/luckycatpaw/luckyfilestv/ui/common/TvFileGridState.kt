package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object TvFileGridDefaults {
    val SafeHorizontalSpace = 56.dp
    val ItemMinWidth = 170.dp
    val ItemHeight = 198.dp
    val HorizontalSpacing = 18.dp
    val VerticalSpacing = 18.dp

    fun columnCount(availableWidth: Dp): Int {
        return (
                (availableWidth.value + HorizontalSpacing.value) /
                        (ItemMinWidth.value + HorizontalSpacing.value)
                ).toInt().coerceAtLeast(1)
    }
}

@Stable
internal class TvFileGridFocusState internal constructor(
    itemCount: Int
) {
    private val itemCount = itemCount

    // Lazy grids only compose a small window. Keep requesters for that window
    // instead of allocating one object for every file in a huge directory.
    private val requesters = mutableMapOf<Int, FocusRequester>()

    private val attachedIndices = mutableSetOf<Int>()

    val indices: IntRange
        get() = 0 until itemCount

    internal fun requesterAt(index: Int): FocusRequester {
        require(index in indices)
        return requesters.getOrPut(index) { FocusRequester() }
    }

    internal fun attach(index: Int, requester: FocusRequester) {
        requesters[index] = requester
        if (index !in attachedIndices) {
            attachedIndices.add(index)
        }
    }

    internal fun detach(index: Int, requester: FocusRequester) {
        attachedIndices.remove(index)
        requesters.remove(index, requester)
    }

    fun requestFocus(index: Int, enabled: Boolean = true): Boolean {
        if (
            !enabled ||
            index !in indices ||
            index !in attachedIndices
        ) {
            return false
        }

        requesters[index]?.requestFocus() ?: return false
        return true
    }
}

@Composable
internal fun rememberTvFileGridFocusState(
    itemKeys: List<*>
): TvFileGridFocusState {
    return remember(itemKeys) {
        TvFileGridFocusState(itemKeys.size)
    }
}
