package com.luckycatpaw.luckyfilestv.ui.browser

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.theme.AppShapes
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.transfer.model.FileConflictPolicy
import com.luckycatpaw.luckyfilestv.ui.common.ActionMenuOverlay
import com.luckycatpaw.luckyfilestv.ui.common.DialogCard
import com.luckycatpaw.luckyfilestv.ui.common.RequestInitialFocus
import com.luckycatpaw.luckyfilestv.ui.common.TvDialogButton
import com.luckycatpaw.luckyfilestv.ui.common.TvLoadingSpinner
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog
import com.luckycatpaw.luckyfilestv.ui.common.TvTextInput
import com.luckycatpaw.luckyfilestv.util.formatBytes

/**
 * The action menu for a single browser item.
 *
 * Only assembles the entries; the dialog itself is [ActionMenuOverlay], which every other
 * short list of choices in the app already uses. Building the card here a second time is how
 * the two drifted apart in spacing before.
 */
@Composable
fun ItemActionMenuOverlay(
    item: BrowserItem,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (() -> Unit)? = null,
    onProperties: (() -> Unit)? = null
) {
    // The first entry present in the menu owns the initial focus. Cancel is not listed:
    // ActionMenuOverlay appends it so no caller can leave the user without a way out.
    val entries = buildList {
        onSelect?.let { add(stringResource(R.string.select) to it) }
        add(stringResource(R.string.rename) to onRename)
        add(stringResource(R.string.copy) to onCopy)
        add(stringResource(R.string.move) to onMove)
        add(stringResource(R.string.delete) to onDelete)
        onProperties?.let { add(stringResource(R.string.properties) to it) }
    }

    ActionMenuOverlay(
        title = item.name,
        focusKey = item.path,
        entries = entries,
        onDismiss = onDismiss
    )
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
        DialogCard(width = 640.dp) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
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
                TvDialogButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.width(160.dp),
                    focusRequester = cancelFocusRequester,
                    upFocusRequester = fieldFocusRequester,
                    onClick = onDismiss
                )
                TvDialogButton(
                    text = confirmLabel,
                    modifier = Modifier.width(180.dp),
                    upFocusRequester = fieldFocusRequester,
                    onClick = { onConfirm(value) }
                )
            }
        }
    }

    RequestInitialFocus(fieldFocusRequester, initialValue)
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
    val applyAllLabel = stringResource(R.string.apply_all_conflicts)

    TvModalDialog(onDismiss = onCancel) {
        DialogCard(width = 700.dp) {
            Text(
                text = stringResource(R.string.file_already_exists),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = sourceName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.target_path, targetDirectory),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(20.dp))

            TvDialogButton(
                text = stringResource(R.string.keep_both),
                modifier = Modifier.fillMaxWidth(),
                focusRequester = keepBothFocus,
                onClick = { onDecision(FileConflictPolicy.KEEP_BOTH, applyToAll) }
            )
            Spacer(Modifier.height(8.dp))
            TvDialogButton(
                text = stringResource(R.string.replace_existing),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDecision(FileConflictPolicy.REPLACE, applyToAll) }
            )
            Spacer(Modifier.height(8.dp))
            TvDialogButton(
                text = stringResource(R.string.skip),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDecision(FileConflictPolicy.SKIP, applyToAll) }
            )

            if (multipleItems) {
                Spacer(Modifier.height(14.dp))
                TvDialogButton(
                    text = if (applyToAll) "✓ $applyAllLabel" else applyAllLabel,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { applyToAll = !applyToAll }
                )
            }

            Spacer(Modifier.height(14.dp))
            TvDialogButton(
                text = stringResource(R.string.cancel),
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel
            )
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
        DialogCard(width = 700.dp) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (totalItems > 1) {
                    stringResource(R.string.transfer_item_progress, currentItem, totalItems)
                } else {
                    currentName
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            if (totalItems > 1) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = currentName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
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
                    style = MaterialTheme.typography.labelMedium
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
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(Modifier.height(22.dp))
            TvDialogButton(
                text = stringResource(R.string.cancel),
                modifier = Modifier.fillMaxWidth(),
                focusRequester = cancelFocus,
                onClick = onCancel
            )
        }
    }

    RequestInitialFocus(cancelFocus, title)
}

@Composable
fun OperationProgressOverlay(message: String) {
    TvModalDialog(onDismiss = {}, dismissOnBackPress = false) {
        DialogCard(
            width = 620.dp,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TvLoadingSpinner(label = "operation-loading")
            Spacer(Modifier.height(18.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    // Transfer progress arrives in bursts — a large file reports nothing for a
    // while, then jumps. Driving the width straight from the value makes the bar
    // twitch; interpolating between reports reads as steady movement, which is
    // what the viewer is watching the dialog for in the first place.
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "transferProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, AppShapes.Bar)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.primary, AppShapes.Bar)
        )
    }
}
