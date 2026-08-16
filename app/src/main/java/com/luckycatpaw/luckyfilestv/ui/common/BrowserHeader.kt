package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
internal fun BrowserHeader(
    title: String,
    buttons: List<HeaderButtonConfig>,
    onDown: () -> Unit,
    focusEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 21.sp,
            fontWeight = FontWeight.Medium,
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
