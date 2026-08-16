package com.luckycatpaw.luckyfilestv.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.data.common.LocalThumbnailDecoder
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.repository.ImageRepository
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.util.FileNameOptimizer
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BrowserGridItem(
    item: BrowserItem,
    selected: Boolean,
    optimizeFileNames: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    useFolderJpgAsIcon: Boolean = true,
    onFocused: (BrowserItem) -> Unit = {}
) {
    val displayName = remember(item.name, optimizeFileNames) {
        if (optimizeFileNames && item is BrowserItem.File) {
            FileNameOptimizer.optimize(item.name)
        } else {
            item.name
        }
    }

    CommonBrowserGridItem(
        displayName = displayName,
        secondaryText = null,
        selected = selected,
        onClick = onClick,
        onFocused = { onFocused(item) },
        modifier = modifier,
        preview = {
            LocalItemPreview(
                item = item,
                selected = selected,
                useFolderJpgAsIcon = useFolderJpgAsIcon
            )
        }
    )
}

@Composable
fun BrowserGridItem(
    item: PickerBrowserItem,
    selected: Boolean,
    optimizeFileNames: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    useFolderJpgAsIcon: Boolean = true,
    onFocused: (PickerBrowserItem) -> Unit = {}
) {
    when (item) {
        is PickerBrowserItem.Local -> {
            BrowserGridItem(
                item = item.item,
                selected = selected,
                optimizeFileNames = optimizeFileNames,
                useFolderJpgAsIcon = useFolderJpgAsIcon,
                onClick = onClick,
                onFocused = { onFocused(item) },
                modifier = modifier
            )
        }

        is PickerBrowserItem.ProviderRoot -> {
            CommonBrowserGridItem(
                displayName = item.name,
                secondaryText = item.root.summary,
                selected = selected,
                onClick = onClick,
                onFocused = { onFocused(item) },
                modifier = modifier,
                preview = {
                    ProviderItemPreview(
                        item = item,
                        selected = selected
                    )
                }
            )
        }

        is PickerBrowserItem.ProviderDocument -> {
            val displayName = remember(item.name, optimizeFileNames) {
                if (optimizeFileNames && !item.document.isDirectory) {
                    FileNameOptimizer.optimize(item.name)
                } else {
                    item.name
                }
            }

            CommonBrowserGridItem(
                displayName = displayName,
                secondaryText = null,
                selected = selected,
                onClick = onClick,
                onFocused = { onFocused(item) },
                modifier = modifier,
                preview = {
                    ProviderItemPreview(
                        item = item,
                        selected = selected
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommonBrowserGridItem(
    displayName: String,
    secondaryText: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier,
    preview: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val lineBreakIndex = displayName.indexOf('\n')
    val primaryText = if (lineBreakIndex >= 0) displayName.substring(0, lineBreakIndex) else displayName
    val secondLine = if (lineBreakIndex >= 0 &&
        lineBreakIndex < displayName.lastIndex
    ) {
        displayName.substring(lineBreakIndex + 1)
    } else {
        secondaryText
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(198.dp)
            .background(backgroundColor, shape)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp),
                contentAlignment = Alignment.Center
            ) {
                preview()
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    MarqueeNameLine(text = primaryText, selected = selected, color = contentColor, fontSize = 15)
                    if (!secondLine.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        MarqueeNameLine(text = secondLine, selected = selected, color = contentColor, fontSize = 14)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeNameLine(text: String, selected: Boolean, color: ComposeColor, fontSize: Int) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        lineHeight = 18.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = if (selected) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 700) else Modifier
            )
    )
}

@Composable
private fun LocalItemPreview(item: BrowserItem, selected: Boolean, useFolderJpgAsIcon: Boolean) {
    val context = LocalContext.current
    val imageRepository = ImageRepository.get(context)

    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = item.path,
        key2 = if (item is BrowserItem.Folder) useFolderJpgAsIcon else null
    ) {
        val previewRequest = withContext(Dispatchers.IO) {
            val source: Pair<String, String>? = when (item) {
                is BrowserItem.Folder -> if (useFolderJpgAsIcon) {
                    LocalThumbnailDecoder.findFolderCover(item.path)?.let { cover -> "folder" to cover }
                } else {
                    null
                }

                is BrowserItem.File ->
                    LocalThumbnailDecoder
                        .previewTypeForExtension(File(item.path).extension.lowercase(Locale.ROOT))
                        ?.let { type -> type to item.path }

                is BrowserItem.Storage -> null
            }

            source?.let { (type, path) ->
                val file = File(path)
                val length = file.length()
                val lastModified = file.lastModified()

                if (!file.isFile) {
                    null
                } else {
                    Triple(type, path, "$type:${file.absolutePath}:$length:$lastModified")
                }
            }
        }

        if (previewRequest == null) {
            value = null
        } else {
            val generated = imageRepository.getLocalThumbnail(previewRequest.third) {
                LocalThumbnailDecoder.decode(context, previewRequest.first, previewRequest.second)
            }
            value = generated?.asImageBitmap()
        }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        )
    } ?: Icon(
        imageVector = localFallbackIcon(item),
        contentDescription = null,
        tint = previewIconColor(selected),
        modifier = Modifier.size(54.dp)
    )
}

@Composable
private fun ProviderItemPreview(item: PickerBrowserItem, selected: Boolean) {
    val context = LocalContext.current
    val imageRepository = ImageRepository.get(context)

    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = item.key, key2 = item) {
        value = when (item) {
            is PickerBrowserItem.ProviderRoot -> imageRepository.getRootIcon(item.root)?.asImageBitmap()

            is PickerBrowserItem.ProviderDocument -> {
                val thumb = imageRepository.getProviderThumbnail(item.document)
                thumb?.asImageBitmap() ?: imageRepository.getProviderIcon(item.document)?.asImageBitmap()
            }

            is PickerBrowserItem.Local -> null
        }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        )
    } ?: Icon(
        imageVector = providerFallbackIcon(item),
        contentDescription = null,
        tint = previewIconColor(selected),
        modifier = Modifier.size(54.dp)
    )
}

@Composable
private fun previewIconColor(selected: Boolean): ComposeColor =
    if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

private fun localFallbackIcon(item: BrowserItem): ImageVector = when (item) {
    is BrowserItem.Storage -> Icons.Filled.Storage
    is BrowserItem.Folder -> Icons.Filled.Folder
    is BrowserItem.File -> fileIconForExtension(File(item.path).extension.lowercase(Locale.ROOT))
}

private fun providerFallbackIcon(item: PickerBrowserItem): ImageVector = when (item) {
    is PickerBrowserItem.ProviderRoot -> Icons.Filled.Storage

    is PickerBrowserItem.ProviderDocument -> when {
        item.document.isDirectory -> Icons.Filled.Folder
        item.document.mimeType.startsWith("image/", ignoreCase = true) -> Icons.Filled.Image
        item.document.mimeType.startsWith("video/", ignoreCase = true) -> Icons.Filled.Movie
        item.document.mimeType.startsWith("audio/", ignoreCase = true) -> Icons.Filled.Audiotrack
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    is PickerBrowserItem.Local -> localFallbackIcon(item.item)
}

private fun fileIconForExtension(extension: String): ImageVector = when (extension) {
    in MimeTypes.IMAGE_EXTENSIONS -> Icons.Filled.Image
    in MimeTypes.VIDEO_EXTENSIONS -> Icons.Filled.Movie
    in MimeTypes.AUDIO_EXTENSIONS -> Icons.Filled.Audiotrack
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
