package com.luckycatpaw.luckyfilestv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

internal val DirectionKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_DPAD_UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN
)

internal val ActivationKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A
)

/** How much a focused element grows. Small: at 1080p a few percent is already obvious. */
internal const val FOCUS_SCALE = 1.06f

/** Long enough to be seen as movement, short enough not to lag a held D-pad. */
internal const val FOCUS_ANIMATION_MILLIS = 140

@Composable
internal fun Modifier.tvFocusable(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    onKeyEvent: (keyCode: Int, keyDown: Boolean) -> Boolean = { _, _ -> false }
): Modifier = this
    .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
    .focusProperties { canFocus = enabled }
    .onFocusChanged { onFocusChanged(it.isFocused && enabled) }
    .onPreviewKeyEvent { event ->
        if (!enabled) {
            true
        } else {
            onKeyEvent(event.nativeKeyEvent.keyCode, event.type == KeyEventType.KeyDown)
        }
    }
    // Indication off. The parameterless clickable falls back to LocalIndication,
    // whose default draws a translucent black wash over the element while it is
    // focused or pressed. On a dark TV theme that reads as a grey box appearing
    // behind whatever is focused, competing with the outline and the growth that
    // are supposed to carry that state.
    .clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled
    ) { onClick() }

/**
 * Grows the element and lifts it onto an accent-bordered surface while focused.
 *
 * On a TV the viewer sits far enough away that a colour swap alone is easy to
 * lose, especially with a grid of similar tiles; movement is what the eye picks
 * up. Neither container is filled by default, so focus reads as an outline and a
 * step forward over the page background rather than as a panel appearing behind
 * the element. Pass a colour for either state to opt back into a fill. The scale is read inside [graphicsLayer]'s lambda, which samples it during
 * the draw phase — the animation therefore costs a frame redraw and not a
 * recomposition per item, which matters in a grid holding thousands of entries.
 */
@Composable
internal fun Modifier.tvFocusHighlight(
    focused: Boolean,
    shape: Shape,
    focusedContainer: Color = Color.Transparent,
    unfocusedContainer: Color = Color.Transparent,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    borderWidth: Dp = 2.dp,
    scaleOnFocus: Boolean = true
): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(FOCUS_ANIMATION_MILLIS, easing = FastOutSlowInEasing),
        label = "tvFocusHighlight"
    )

    return this
        .then(if (scaleOnFocus) Modifier.focusGrowth { progress } else Modifier)
        .then(
            if (unfocusedContainer == Color.Transparent && focusedContainer == Color.Transparent) {
                Modifier
            } else {
                Modifier.background(lerp(unfocusedContainer, focusedContainer, progress), shape)
            }
        )
        .then(
            if (progress > 0f) {
                Modifier.border(borderWidth, borderColor.copy(alpha = progress), shape)
            } else {
                Modifier
            }
        )
}

/**
 * The growth half of [tvFocusHighlight], for callers that paint their own container.
 *
 * The file grid needs this separately because a tile is not one box: the selection
 * badge is a sibling of the tile content, and if only the tile grew the badge would
 * visibly drift away from the corner it is pinned to. Applying the growth to the node
 * holding both keeps them together.
 */
@Composable
internal fun Modifier.tvFocusScale(focused: Boolean): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(FOCUS_ANIMATION_MILLIS, easing = FastOutSlowInEasing),
        label = "tvFocusScale"
    )

    return this.focusGrowth { progress }
}

/**
 * Draws the content enlarged without telling the layout system about it.
 *
 * This deliberately does not use `graphicsLayer`. A layer transform is reported back
 * through [androidx.compose.ui.layout.LayoutCoordinates], so an ancestor that asks
 * where a child sits gets the scaled rectangle — and inside a lazy grid the thing
 * asking is the automatic scroll that `focusable` performs when a node takes focus.
 * A tile that grows on focus therefore looked to that machinery like a tile that no
 * longer fit, and the grid scrolled to fix a problem that only existed while drawing.
 * A draw-time transform is invisible to layout, so nothing upstream reacts to it.
 *
 * [progress] is read inside the draw lambda on purpose: that makes the animation a
 * draw invalidation rather than a recomposition, which is what keeps it cheap in a
 * grid holding thousands of items.
 */
private fun Modifier.focusGrowth(progress: () -> Float): Modifier = drawWithContent {
    val factor = 1f + (FOCUS_SCALE - 1f) * progress()

    if (factor == 1f) {
        drawContent()
    } else {
        scale(factor) {
            this@drawWithContent.drawContent()
        }
    }
}

/** Content colour matching [tvFocusHighlight]. */
@Composable
internal fun tvContentColor(focused: Boolean): Color =
    if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
