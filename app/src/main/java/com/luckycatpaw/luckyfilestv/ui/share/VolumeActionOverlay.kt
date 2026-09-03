package com.luckycatpaw.luckyfilestv.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.source.Volume
import com.luckycatpaw.luckyfilestv.ui.common.ActionMenuOverlay

/**
 * Menu of a volume tile.
 *
 * Only reached for volumes the user configured: internal storage and removable media come
 * from the system and report no configuration, so the browser never opens this menu for
 * them.
 */
@Composable
internal fun VolumeActionMenuOverlay(
    volume: Volume,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    ActionMenuOverlay(
        title = volume.name,
        focusKey = volume.path.value,
        entries = listOf(
            stringResource(R.string.volume_settings) to onSettings,
            stringResource(R.string.volume_remove) to onRemove
        ),
        onDismiss = onDismiss
    )
}
