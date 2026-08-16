package com.luckycatpaw.luckyfilestv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
    .clickable(enabled = enabled) { onClick() }

@Composable
internal fun Modifier.tvFocusHighlight(
    focused: Boolean,
    shape: Shape,
    focusedContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    unfocusedContainer: Color = MaterialTheme.colorScheme.surfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    borderWidth: Dp = 2.dp
): Modifier = this
    .background(if (focused) focusedContainer else unfocusedContainer, shape)
    .then(if (focused) Modifier.border(borderWidth, borderColor, shape) else Modifier)

/** Content colour matching [tvFocusHighlight]. */
@Composable
internal fun tvContentColor(focused: Boolean): Color =
    if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
