package com.luckycatpaw.luckyfilestv.ui.picker

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog
import com.luckycatpaw.luckyfilestv.R

@Composable
fun ProviderStatusOverlay(
    loading: Boolean,
    info: String?,
    error: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onDismissInfo: () -> Unit
) {
    if (!error.isNullOrBlank()) {
        ProviderErrorOverlay(
            message = error,
            onRetry = onRetry,
            onDismiss = onDismissError
        )
        return
    }

    val message = when {
        loading && !info.isNullOrBlank() -> info
        loading -> stringResource(R.string.provider_loading_more)
        !info.isNullOrBlank() -> info
        else -> null
    } ?: return

    ProviderInfoOverlay(
        message = message,
        loading = loading,
        onDismiss = onDismissInfo
    )
}

@Composable
private fun ProviderInfoOverlay(
    message: String,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    val closeRequester = remember { FocusRequester() }

    TvModalDialog(
        onDismiss = {
            if (!loading) onDismiss()
        },
        dismissOnBackPress = !loading,
        dimAlpha = 0.42f
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(16.dp)
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    RoundedCornerShape(16.dp)
                )
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (loading) {
                ProviderLoadingSpinner()
            }

            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )

            if (!loading) {
                ProviderDialogButton(
                    text = stringResource(R.string.close),
                    focusRequester = closeRequester,
                    onClick = onDismiss
                )
            }
        }
    }

    if (!loading) {
        LaunchedEffect(message) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { closeRequester.requestFocus() }
        }
    }
}

@Composable
private fun ProviderLoadingSpinner() {
    val transition = rememberInfiniteTransition(label = "provider-loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 850,
                easing = LinearEasing
            )
        ),
        label = "provider-loading-rotation"
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(40.dp)) {
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 275f,
            useCenter = false,
            style = Stroke(
                width = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}

@Composable
private fun ProviderErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val retryRequester = remember { FocusRequester() }

    TvModalDialog(
        onDismiss = onDismiss,
        dimAlpha = 0.68f
    ) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(16.dp)
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(16.dp)
                )
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.storage_source_unavailable),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )

            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProviderDialogButton(
                    text = stringResource(R.string.back),
                    onClick = onDismiss
                )

                ProviderDialogButton(
                    text = stringResource(R.string.retry),
                    focusRequester = retryRequester,
                    onClick = onRetry
                )
            }
        }
    }

    LaunchedEffect(message) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { retryRequester.requestFocus() }
    }
}

@Composable
private fun ProviderDialogButton(
    text: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)

    Row(
        modifier = Modifier
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode

                event.type == KeyEventType.KeyDown &&
                (keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            }
            .background(
                if (focused) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape
            )
            .then(
                if (focused) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        shape
                    )
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(
                horizontal = 18.dp,
                vertical = 10.dp
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = if (focused) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 14.sp
        )
    }
}
