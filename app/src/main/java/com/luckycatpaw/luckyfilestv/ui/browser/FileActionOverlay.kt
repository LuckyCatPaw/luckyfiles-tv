package com.luckycatpaw.luckyfilestv.ui.browser

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.data.transfer.model.FileConflictPolicy
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog
import com.luckycatpaw.luckyfilestv.ui.common.TvTextInput
import java.util.Locale

@Composable
fun ItemActionMenuOverlay(
    item: BrowserItem,
    onSelect: (() -> Unit)? = null,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onProperties: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text(
                text = item.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.dp))

            if (onSelect != null) {
                ActionButton(
                    text = stringResource(R.string.select),
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = firstFocus,
                    onClick = onSelect
                )
                Spacer(Modifier.height(8.dp))
                ActionButton(stringResource(R.string.rename), modifier = Modifier.fillMaxWidth(), onClick = onRename)
            } else {
                ActionButton(
                    text = stringResource(R.string.rename),
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = firstFocus,
                    onClick = onRename
                )
            }

            Spacer(Modifier.height(8.dp))
            ActionButton(stringResource(R.string.copy), modifier = Modifier.fillMaxWidth(), onClick = onCopy)
            Spacer(Modifier.height(8.dp))
            ActionButton(stringResource(R.string.move), modifier = Modifier.fillMaxWidth(), onClick = onMove)
            Spacer(Modifier.height(8.dp))
            ActionButton(stringResource(R.string.delete), modifier = Modifier.fillMaxWidth(), onClick = onDelete)

            if (onProperties != null) {
                Spacer(Modifier.height(8.dp))
                ActionButton(stringResource(R.string.properties), modifier = Modifier.fillMaxWidth(), onClick = onProperties)
            }

            Spacer(Modifier.height(8.dp))
            ActionButton(stringResource(R.string.cancel), modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }

    RequestInitialFocus(firstFocus, item.path)
}

@Composable
fun NameInputOverlay(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val fieldFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(20.dp))

            TvTextInput(
                value = value,
                onValueChange = { value = it },
                focusRequester = fieldFocusRequester,
                downFocusRequester = cancelFocusRequester
            )

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                ActionButton(
                    text = stringResource(R.string.cancel),
                    focusRequester = cancelFocusRequester,
                    upFocusRequester = fieldFocusRequester,
                    modifier = Modifier.width(160.dp),
                    onClick = onDismiss
                )
                ActionButton(
                    text = confirmLabel,
                    upFocusRequester = fieldFocusRequester,
                    modifier = Modifier.width(180.dp),
                    onClick = { onConfirm(value) }
                )
            }
        }
    }

    RequestInitialFocus(fieldFocusRequester, initialValue)
}

@Composable
fun DeleteConfirmOverlay(
    item: BrowserItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            Text(
                text = stringResource(R.string.confirm_delete),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = item.name,
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
                ActionButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.width(170.dp),
                    focusRequester = cancelFocus,
                    onClick = onDismiss
                )
                ActionButton(stringResource(R.string.delete), modifier = Modifier.width(170.dp), onClick = onConfirm)
            }
        }
    }

    RequestInitialFocus(cancelFocus, item.path)
}

@Composable
fun MultiDeleteConfirmOverlay(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            Text(
                text = stringResource(R.string.confirm_delete_count, count),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.confirm_delete_selected_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                ActionButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.width(170.dp),
                    focusRequester = cancelFocus,
                    onClick = onDismiss
                )
                ActionButton(stringResource(R.string.delete), modifier = Modifier.width(170.dp), onClick = onConfirm)
            }
        }
    }

    RequestInitialFocus(cancelFocus, count)
}

@Composable
fun TransferConflictOverlay(
    sourceName: String,
    targetDirectory: String,
    multipleItems: Boolean,
    onDecision: (FileConflictPolicy, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var applyToAll by remember(multipleItems) { mutableStateOf(false) }
    val keepBothFocus = remember { FocusRequester() }

    TvModalDialog(onDismiss = onCancel) {
        Column(
            modifier = Modifier
                .width(700.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            Text(
                text = stringResource(R.string.file_already_exists),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = sourceName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.target_path, targetDirectory),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(20.dp))

            ActionButton(
                text = stringResource(R.string.keep_both),
                focusRequester = keepBothFocus,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDecision(FileConflictPolicy.KEEP_BOTH, applyToAll) }
            )
            Spacer(Modifier.height(8.dp))
            ActionButton(
                text = stringResource(R.string.replace_existing),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDecision(FileConflictPolicy.REPLACE, applyToAll) }
            )
            Spacer(Modifier.height(8.dp))
            ActionButton(
                text = stringResource(R.string.skip),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDecision(FileConflictPolicy.SKIP, applyToAll) }
            )

            if (multipleItems) {
                Spacer(Modifier.height(14.dp))
                ActionButton(
                    text = if (applyToAll) {
                        "✓ ${stringResource(R.string.apply_all_conflicts)}"
                    } else {
                        stringResource(R.string.apply_all_conflicts)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { applyToAll = !applyToAll }
                )
            }

            Spacer(Modifier.height(14.dp))
            ActionButton(stringResource(R.string.cancel), modifier = Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }

    RequestInitialFocus(keepBothFocus, sourceName)
}

@Composable
fun TransferProgressOverlay(
    title: String,
    currentItem: Int,
    totalItems: Int,
    currentName: String,
    bytesProcessed: Long,
    totalBytes: Long,
    bytesPerSecond: Long?,
    onCancel: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }
    val progress = when {
        totalBytes > 0L -> (bytesProcessed.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        totalItems > 0 -> (currentItem.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    TvModalDialog(onDismiss = onCancel) {
        Column(
            modifier = Modifier
                .width(700.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (totalItems > 1) stringResource(R.string.transfer_item_progress, currentItem, totalItems) else currentName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
            if (totalItems > 1) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = currentName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))
            ProgressBar(progress)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(progress * 100f).toInt()} %",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Text(
                    text = buildString {
                        append(formatBytes(bytesProcessed))
                        if (totalBytes > 0L) {
                            append(" / ")
                            append(formatBytes(totalBytes))
                        }
                        if (bytesPerSecond != null && bytesPerSecond > 0L) {
                            append("  ·  ")
                            append(formatBytes(bytesPerSecond))
                            append("/s")
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(22.dp))
            ActionButton(
                text = stringResource(R.string.cancel),
                focusRequester = cancelFocus,
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel
            )
        }
    }

    RequestInitialFocus(cancelFocus, title)
}

@Composable
fun OperationProgressOverlay(message: String) {
    TvModalDialog(
        onDismiss = {},
        dismissOnBackPress = false
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingSpinner()
            Spacer(Modifier.height(18.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(10.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
        )
    }
}

@Composable
private fun LoadingSpinner() {
    val transition = rememberInfiniteTransition(label = "operation-loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing)
        ),
        label = "operation-spinner"
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(Modifier.size(38.dp)) {
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 275f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                if (
                    upFocusRequester != null &&
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        upFocusRequester.requestFocus()
                    }
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape
            )
            .then(
                if (focused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RequestInitialFocus(
    focusRequester: FocusRequester,
    key: Any?
) {
    LaunchedEffect(key) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val tb = gb * 1024.0

    return when {
        value >= tb -> String.format(Locale.getDefault(), "%.2f TB", value / tb)
        value >= gb -> String.format(Locale.getDefault(), "%.2f GB", value / gb)
        value >= mb -> String.format(Locale.getDefault(), "%.1f MB", value / mb)
        value >= kb -> String.format(Locale.getDefault(), "%.1f KB", value / kb)
        else -> "$value B"
    }
}
