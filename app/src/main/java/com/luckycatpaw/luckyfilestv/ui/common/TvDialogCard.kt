package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.R

private val CardShape = RoundedCornerShape(16.dp)

@Composable
internal fun DialogCard(
    width: Dp,
    modifier: Modifier = Modifier,
    padding: Dp = 28.dp,
    borderAlpha: Float = 0.4f,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .width(width)
            .background(MaterialTheme.colorScheme.surface, CardShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha), CardShape)
            .padding(padding),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        content = content
    )
}

@Composable
internal fun RequestInitialFocus(focusRequester: FocusRequester, key: Any?) {
    LaunchedEffect(key) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
internal fun ConfirmOverlay(
    title: String,
    message: String,
    confirmLabel: String,
    focusKey: Any?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss) {
        DialogCard(width = 620.dp) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TvDialogButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.width(170.dp),
                    focusRequester = cancelFocus,
                    onClick = onDismiss
                )
                TvDialogButton(
                    text = confirmLabel,
                    modifier = Modifier.width(170.dp),
                    onClick = onConfirm
                )
            }
        }
    }

    RequestInitialFocus(cancelFocus, focusKey)
}

/**
 * Vertical list of actions in a dialog.
 *
 * Shared by the header menu and every other place that offers a short list of choices: they
 * differ in their title and entries, not in their behaviour. Cancel is appended here so no
 * caller can forget the way out, and the first entry owns the initial focus.
 */
@Composable
internal fun ActionMenuOverlay(
    title: String,
    focusKey: Any?,
    entries: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    val allEntries = entries + (stringResource(R.string.cancel) to onDismiss)

    TvModalDialog(onDismiss = onDismiss) {
        DialogCard(width = 560.dp, padding = 24.dp) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.dp))

            allEntries.forEachIndexed { index, (label, action) ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                TvDialogButton(
                    text = label,
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = firstFocus.takeIf { index == 0 },
                    onClick = action
                )
            }
        }
    }

    RequestInitialFocus(firstFocus, focusKey)
}

@Composable
internal fun TvDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    trapVerticalKeys: Boolean = false,
    fontSize: Int = 16
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .tvFocusable(
                onClick = onClick,
                focusRequester = focusRequester,
                onFocusChanged = { focused = it },
                onKeyEvent = { keyCode, keyDown ->
                    when {
                        upFocusRequester != null &&
                            keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (keyDown) upFocusRequester.requestFocus()
                            true
                        }

                        trapVerticalKeys && keyCode in VerticalKeyCodes -> keyDown

                        else -> false
                    }
                }
            )
            .tvFocusHighlight(focused, shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = tvContentColor(focused),
            fontSize = fontSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val VerticalKeyCodes = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP,
    android.view.KeyEvent.KEYCODE_DPAD_DOWN
)
