package com.luckycatpaw.luckyfilestv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.ui.theme.AppShapes

/** Shared height for every button in the header row, icon or label. */
internal val HeaderButtonHeight = 36.dp

@Composable
internal fun BrowserHeaderButton(
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onDown: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    focusEnabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AppShapes.Control
    val iconOnly = icon != null && text == null

    Box(
        modifier = modifier
            // Both kinds of button are pinned to the same height rather than each
            // taking the size of what it holds. An icon button was a fixed 40 dp
            // box while a labelled one came out to its line height plus padding,
            // so the two never matched and the row looked ragged.
            .height(HeaderButtonHeight)
            .then(if (iconOnly) Modifier.width(HeaderButtonHeight) else Modifier)
            .tvFocusable(
                onClick = onClick,
                focusRequester = focusRequester,
                enabled = focusEnabled,
                onFocusChanged = { isFocused ->
                    focused = isFocused
                    if (isFocused) onFocused()
                },
                onKeyEvent = { keyCode, keyDown ->
                    when (keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (keyDown) onDown()
                            true
                        }

                        // Up must not escape the header row.
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> true

                        else -> false
                    }
                }
            )
            .tvFocusHighlight(focused = focused, shape = shape)
            .then(if (text != null) Modifier.padding(horizontal = 14.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                color = tvContentColor(focused),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tvContentColor(focused),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
