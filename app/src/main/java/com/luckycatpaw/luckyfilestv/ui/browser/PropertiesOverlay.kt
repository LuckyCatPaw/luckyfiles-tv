package com.luckycatpaw.luckyfilestv.ui.browser

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun PropertiesOverlay(
    itemName: String,
    properties: FileProperties?,
    error: String?,
    onDismiss: () -> Unit
) {
    val closeFocus = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss, dimAlpha = 0.82f) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            Text(
                text = stringResource(R.string.properties),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 23.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(20.dp))

            when {
                properties == null && error == null -> LoadingProperties(itemName)
                error != null -> Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp
                )
                properties != null -> {
                    PropertyRow(stringResource(R.string.properties_name), properties.name)
                    PropertyRow(stringResource(R.string.properties_type), formatType(properties))
                    PropertyRow(stringResource(R.string.properties_size), formatBytes(properties.size))

                    if (properties.isDirectory) {
                        val fileQuantity = properties.fileCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                        val folderQuantity = properties.folderCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                        val fileCount = pluralStringResource(R.plurals.properties_file_count, fileQuantity, properties.fileCount)
                        val folderCount = pluralStringResource(R.plurals.properties_folder_count, folderQuantity, properties.folderCount)
                        PropertyRow(
                            stringResource(R.string.properties_contents),
                            stringResource(R.string.properties_content_counts, fileCount, folderCount)
                        )
                    }

                    PropertyRow(stringResource(R.string.properties_modified), formatDate(properties.lastModified))
                    PropertyRow(stringResource(R.string.properties_path), properties.path)

                    if (!properties.mimeType.isNullOrBlank()) {
                        PropertyRow("MIME", properties.mimeType)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            CloseButton(closeFocus, onDismiss)
        }
    }

    LaunchedEffect(itemName) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }
}

@Composable
private fun LoadingProperties(itemName: String) {
    val transition = rememberInfiniteTransition(label = "properties-loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(850, easing = LinearEasing)),
        label = "properties-spinner"
    )
    val color = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(38.dp)) {
            drawArc(
                color = color,
                startAngle = rotation,
                sweepAngle = 275f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Text(
            text = stringResource(R.string.properties_calculating, itemName),
            modifier = Modifier.padding(start = 18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.width(130.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun CloseButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.close),
            color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun formatType(properties: FileProperties): String {
    if (properties.isDirectory) return stringResource(R.string.type_folder)
    return properties.extension
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.getDefault())
        ?.let { stringResource(R.string.type_file_extension, it) }
        ?: stringResource(R.string.type_file)
}

@Composable
private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return stringResource(R.string.unknown)
    return DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
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
