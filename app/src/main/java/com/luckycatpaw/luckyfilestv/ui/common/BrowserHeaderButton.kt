package com.luckycatpaw.luckyfilestv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    var focused by remember {
        mutableStateOf(false)
    }

    val shape = RoundedCornerShape(9.dp)

    Box(
        modifier = modifier
            .then(
                if (icon != null && text == null) {
                    Modifier.size(40.dp)
                } else {
                    Modifier
                }
            )
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = focusEnabled
            }
            .onFocusChanged { state ->
                focused = state.isFocused && focusEnabled
                if (state.isFocused && focusEnabled) {
                    onFocused()
                }
            }
            .onPreviewKeyEvent { event ->
                if (!focusEnabled) {
                    return@onPreviewKeyEvent true
                }

                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.type == KeyEventType.KeyDown) {
                            onDown()
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
            .background(
                if (focused) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape
            )
            .then(
                if (focused) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                enabled = focusEnabled
            ) {
                onClick()
            }
            .then(
                if (text != null) {
                    Modifier.padding(
                        horizontal = 15.dp,
                        vertical = 8.dp
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                color = if (focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 14.sp,
                maxLines = 1
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
