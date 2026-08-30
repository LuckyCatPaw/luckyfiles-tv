package com.luckycatpaw.luckyfilestv.ui.browser

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.ui.common.DialogCard
import com.luckycatpaw.luckyfilestv.ui.common.RequestInitialFocus
import com.luckycatpaw.luckyfilestv.ui.common.TvDialogButton
import com.luckycatpaw.luckyfilestv.ui.common.TvLoadingSpinner
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog
import com.luckycatpaw.luckyfilestv.util.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun PropertiesOverlay(itemName: String, properties: FileProperties?, error: String?, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }

    TvModalDialog(onDismiss = onDismiss, dimAlpha = 0.82f) {
        DialogCard(width = 720.dp) {
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

                properties != null -> PropertyList(properties)
            }

            Spacer(Modifier.height(24.dp))
            TvDialogButton(
                text = stringResource(R.string.close),
                modifier = Modifier.fillMaxWidth(),
                focusRequester = closeFocus,
                onClick = onDismiss
            )
        }
    }

    RequestInitialFocus(closeFocus, itemName)
}

@Composable
private fun PropertyList(properties: FileProperties) {
    PropertyRow(stringResource(R.string.properties_name), properties.name)
    PropertyRow(stringResource(R.string.properties_type), formatType(properties))
    PropertyRow(stringResource(R.string.properties_size), formatBytes(properties.size))

    if (properties.isDirectory) {
        val fileQuantity = properties.fileCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val folderQuantity = properties.folderCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val fileCount = pluralStringResource(
            R.plurals.properties_file_count,
            fileQuantity,
            properties.fileCount
        )
        val folderCount = pluralStringResource(
            R.plurals.properties_folder_count,
            folderQuantity,
            properties.folderCount
        )
        PropertyRow(
            stringResource(R.string.properties_contents),
            stringResource(R.string.properties_content_counts, fileCount, folderCount)
        )
    }

    if (properties.unreadableDirectoryCount > 0L) {
        PropertyRow(
            stringResource(R.string.properties_incomplete),
            pluralStringResource(
                R.plurals.properties_unreadable_folders,
                properties.unreadableDirectoryCount.toInt(),
                properties.unreadableDirectoryCount
            )
        )
    }

    PropertyRow(stringResource(R.string.properties_modified), formatDate(properties.lastModified))
    PropertyRow(stringResource(R.string.properties_path), properties.path)

    if (!properties.mimeType.isNullOrBlank()) {
        PropertyRow("MIME", properties.mimeType)
    }
}

@Composable
private fun LoadingProperties(itemName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvLoadingSpinner(label = "properties-loading")
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
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
private fun formatType(properties: FileProperties): String {
    if (properties.isDirectory) return stringResource(R.string.type_folder)
    val locale = LocalConfiguration.current.locales[0]
    return properties.extension
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(locale)
        ?.let { stringResource(R.string.type_file_extension, it) }
        ?: stringResource(R.string.type_file)
}

@Composable
private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return stringResource(R.string.unknown)
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
}
