package com.luckycatpaw.luckyfilestv.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.common.DialogCard
import com.luckycatpaw.luckyfilestv.ui.common.RequestInitialFocus
import com.luckycatpaw.luckyfilestv.ui.common.TvDialogButton
import com.luckycatpaw.luckyfilestv.ui.common.TvLoadingSpinner
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog

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
        ProviderErrorOverlay(message = error, onRetry = onRetry, onDismiss = onDismissError)
        return
    }

    val message = when {
        loading && !info.isNullOrBlank() -> info
        loading -> stringResource(R.string.provider_loading_more)
        !info.isNullOrBlank() -> info
        else -> null
    } ?: return

    ProviderInfoOverlay(message = message, loading = loading, onDismiss = onDismissInfo)
}

@Composable
private fun ProviderInfoOverlay(message: String, loading: Boolean, onDismiss: () -> Unit) {
    val closeRequester = remember { FocusRequester() }

    TvModalDialog(
        onDismiss = { if (!loading) onDismiss() },
        dismissOnBackPress = !loading,
        dimAlpha = 0.42f
    ) {
        DialogCard(
            width = 560.dp,
            padding = 26.dp,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (loading) {
                TvLoadingSpinner(label = "provider-loading", size = 40.dp)
            }

            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )

            if (!loading) {
                TvDialogButton(
                    text = stringResource(R.string.close),
                    focusRequester = closeRequester,
                    trapVerticalKeys = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    onClick = onDismiss
                )
            }
        }
    }

    if (!loading) {
        RequestInitialFocus(closeRequester, message)
    }
}

@Composable
private fun ProviderErrorOverlay(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val retryRequester = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss, dimAlpha = 0.68f) {
        DialogCard(
            width = 640.dp,
            borderColor = MaterialTheme.colorScheme.error,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.storage_source_unavailable),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvDialogButton(
                    text = stringResource(R.string.back),
                    trapVerticalKeys = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    onClick = onDismiss
                )

                TvDialogButton(
                    text = stringResource(R.string.retry),
                    focusRequester = retryRequester,
                    trapVerticalKeys = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    onClick = onRetry
                )
            }
        }
    }

    RequestInitialFocus(retryRequester, message)
}
