package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frames a composed item may spend waiting to become focusable.
 *
 * This covers the gap between attached and placed only: a focus target rejects focus
 * until it has been placed, which happens on the next layout pass. It is deliberately
 * not a budget for waiting on composition — that wait is what [TvFileGridFocusState]
 * removes by letting the item announce itself instead.
 */
private const val PLACEMENT_FRAMES = 3

internal object TvFileGridDefaults {
    val SafeHorizontalSpace = 56.dp
    val ItemMinWidth = 170.dp
    val ItemHeight = 198.dp
    val HorizontalSpacing = 18.dp
    val VerticalSpacing = 18.dp
    val VerticalScrollPadding = 32.dp

    fun columnCount(availableWidth: Dp): Int = (
        (availableWidth.value + HorizontalSpacing.value) /
            (ItemMinWidth.value + HorizontalSpacing.value)
        ).toInt().coerceAtLeast(1)
}

/**
 * Focus coordination for a lazy grid.
 *
 * Callers do not focus an index; they *declare* which index should hold focus via
 * [focusIndex]. If the item happens to be on screen the request is served straight
 * away. If it is not, [requestedIndex] stays set and the item claims focus itself the
 * moment it composes. Nobody has to guess how long composition takes, which is what
 * made the previous frame-counting approach fragile on slow hardware.
 */
@Stable
internal class TvFileGridFocusState internal constructor(private var itemCount: Int) {
    // Lazy grids only compose a small window. Keep requesters for that window
    // instead of allocating one object for every file in a huge directory.
    private val requesters = mutableMapOf<Int, FocusRequester>()

    fun updateItemCount(newCount: Int) {
        itemCount = newCount
    }

    /**
     * The index that should hold focus, or -1 once the request has been served or
     * withdrawn. Snapshot state so composed items can observe it without polling.
     */
    var requestedIndex by mutableIntStateOf(-1)
        private set

    val indices: IntRange
        get() = 0 until itemCount

    internal fun requesterAt(index: Int): FocusRequester {
        require(index in indices)
        return requesters.getOrPut(index) { FocusRequester() }
    }

    internal fun register(index: Int, requester: FocusRequester) {
        requesters[index] = requester
    }

    internal fun release(index: Int, requester: FocusRequester) {
        requesters.remove(index, requester)

        if (requestedIndex == index) {
            // The item went away before it could take focus. Leaving the request
            // standing would make it fire again once the index is recycled for a
            // different file.
            requestedIndex = -1
        }
    }

    /**
     * Declares [index] as the focus target.
     *
     * Returns true if focus was granted immediately. A false result is not a failure:
     * the request is latched and will be served by the item once it exists.
     */
    fun focusIndex(index: Int): Boolean {
        if (index !in indices) {
            requestedIndex = -1
            return false
        }

        if (tryFocus(index)) {
            requestedIndex = -1
            return true
        }

        requestedIndex = index
        return false
    }

    /** Withdraws an outstanding request, e.g. when the user navigates elsewhere. */
    fun cancelPendingFocus() {
        requestedIndex = -1
    }

    /**
     * Called by a composed item that sees itself named in [requestedIndex].
     *
     * Composed is not the same as placed, so a handful of frames are allowed here.
     * Unlike a wait for composition this latency is bounded and known — one layout
     * pass — so counting frames is appropriate rather than a guess.
     */
    internal suspend fun claim(index: Int) {
        repeat(PLACEMENT_FRAMES) { attempt ->
            if (requestedIndex != index) return

            if (tryFocus(index)) {
                requestedIndex = -1
                return
            }

            if (attempt < PLACEMENT_FRAMES - 1) {
                withFrameNanos { }
            }
        }
    }

    private fun tryFocus(index: Int): Boolean {
        val requester = requesters[index] ?: return false

        // requestFocus throws when no node is attached to the requester, which races
        // with recycling during fast scrolling. The direction-taking overload is used
        // because the no-argument one returns Unit and would hide the outcome.
        return runCatching { requester.requestFocus(FocusDirection.Enter) }
            .getOrDefault(false)
    }
}

@Composable
internal fun rememberTvFileGridFocusState(itemKeys: List<*>, key: Any? = Unit): TvFileGridFocusState {
    val state = remember(key) {
        TvFileGridFocusState(itemKeys.size)
    }

    androidx.compose.runtime.SideEffect {
        state.updateItemCount(itemKeys.size)
    }

    return state
}
