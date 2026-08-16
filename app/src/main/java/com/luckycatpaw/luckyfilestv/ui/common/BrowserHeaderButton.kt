package com.luckycatpaw.luckyfilestv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

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
    val shape = RoundedCornerShape(9.dp)
    val iconOnly = icon != null && text == null

    Box(
        modifier = modifier
            .then(if (iconOnly) Modifier.size(40.dp) else Modifier)
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
            .tvFocusHighlight(
                focused = focused,
                shape = shape,
                unfocusedContainer = MaterialTheme.colorScheme.surface
            )
            .then(if (text != null) Modifier.padding(horizontal = 15.dp, vertical = 8.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                color = tvContentColor(focused),
                fontSize = 14.sp,
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
