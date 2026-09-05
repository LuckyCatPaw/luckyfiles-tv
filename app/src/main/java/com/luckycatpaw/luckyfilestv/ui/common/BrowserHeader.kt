package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

internal data class HeaderButtonConfig(
    val text: String? = null,
    val icon: ImageVector? = null,
    val contentDescription: String? = null,
    val focusRequester: FocusRequester,
    val onFocused: () -> Unit,
    val onClick: () -> Unit,
    val visible: Boolean = true
)

/**
 * The button row above the file grid.
 *
 * The row is a focus group addressed through [focusRequester]. `focusRestorer` makes
 * it remember which button was focused when the user left it, so callers do not have
 * to name a button to return to — coming back up from the grid lands where the user
 * was. If nothing is saved yet, the default enter behaviour picks the first button.
 */
@Composable
internal fun BrowserHeader(
    title: String,
    buttons: List<HeaderButtonConfig>,
    onDown: () -> Unit,
    focusRequester: FocusRequester,
    focusEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            // Matches the cell margin in the grid, so the title starts on the
            // same vertical line as the first tile.
            .padding(horizontal = TvFileGridDefaults.TileInset)
            .focusRequester(focusRequester)
            .focusRestorer()
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        buttons.filter { it.visible }.forEach { config ->
            BrowserHeaderButton(
                text = config.text,
                icon = config.icon,
                contentDescription = config.contentDescription,
                focusEnabled = focusEnabled,
                focusRequester = config.focusRequester,
                onFocused = config.onFocused,
                onDown = onDown,
                onClick = config.onClick
            )
        }
    }
}
