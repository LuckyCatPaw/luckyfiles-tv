package com.luckycatpaw.luckyfilestv.ui.browser

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.data.common.LocalThumbnailDecoder
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.VolumeKind
import com.luckycatpaw.luckyfilestv.data.repository.ImageRepository
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGridDefaults
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.theme.AppShapes
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
    // The name sits below the card rather than inside it. Inside, a long file name
    // eats into the space the thumbnail needs and every tile ends up a different
    // shape; outside, the cards form an even row and the text can run as wide as
    // it likes. Start alignment for the same reason a file list has one: names are
    // scanned by their first characters, and centring puts those on a ragged edge.
    val nameColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val lineBreakIndex = remember(displayName) { displayName.indexOf('\n') }
    val primaryText = remember(displayName, lineBreakIndex) {
        if (lineBreakIndex >= 0) displayName.substring(0, lineBreakIndex) else displayName
    }
    val secondLine = remember(displayName, lineBreakIndex, secondaryText) {
        if (lineBreakIndex >= 0 && lineBreakIndex < displayName.lastIndex) {
            displayName.substring(lineBreakIndex + 1)
        } else {
            secondaryText
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(TvFileGridDefaults.TileHeight)
            .onFocusChanged { if (it.isFocused) onFocused() }
            // See tvFocusable: the default indication paints a grey wash on focus.
            .clickable(
                interactionSource = null,
                indication = null
            ) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvFileGridDefaults.ItemPreviewHeight)
                // No fill in either state: the preview area is a frame over the
                // page background, so a thumbnail is not sitting on a second grey.
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.borderVariant
                    },
                    shape = AppShapes.Tile
                )
                // Without the clip a wide thumbnail draws over the rounded corners.
                .clip(AppShapes.Tile)
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            preview()
        }

        Spacer(modifier = Modifier.height(TvFileGridDefaults.ItemLabelSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvFileGridDefaults.ItemLabelHeight),
            verticalArrangement = Arrangement.Top
        ) {
            MarqueeNameLine(
                text = primaryText,
                selected = selected,
                color = nameColor,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!secondLine.isNullOrBlank()) {
                MarqueeNameLine(
                    text = secondLine,
                    selected = selected,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeNameLine(
    text: String,
    selected: Boolean,
    color: ComposeColor,
    style: TextStyle
) {
    Text(
        text = text,
        color = color,
        style = style,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        textAlign = TextAlign.Start,
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

    // Keyed on the item rather than on its path: the path is what a replaced file keeps and
    // its size and write time are what change, so keying on the path would compute a fresh
    // cache key and then never ask for it while the tile stays composed.
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = item,
        key2 = if (item is BrowserItem.Folder) useFolderJpgAsIcon else null
    ) {
        val previewRequest = withContext(Dispatchers.IO) {
            val location = SourcePath.parseOrNull(item.path)
            val cheapMetadata = LocalThumbnailDecoder.hasCheapMetadata(context, item.path)

            val source: Pair<String, String>? = when (item) {
                // A folder cover means listing the directory, which is one round trip per
                // tile where that is not free.
                is BrowserItem.Folder -> if (useFolderJpgAsIcon && cheapMetadata) {
                    LocalThumbnailDecoder.findFolderCover(item.path)?.let { cover -> "folder" to cover }
                } else {
                    null
                }

                is BrowserItem.File ->
                    LocalThumbnailDecoder
                        .previewTypeForExtension(
                            location?.extension ?: File(item.path).extension.lowercase(Locale.ROOT)
                        )
                        ?.let { type -> type to item.path }

                is BrowserItem.Storage -> null
            }

            source?.let { (type, path) ->
                if (!cheapMetadata) {
                    // Stat-ing the file here would be a request per tile, so the two values
                    // that identify its content come from the listing that drew the grid
                    // instead: on a share they arrive in the same directory response as the
                    // name, at no extra cost. Without them the key was the location alone,
                    // and a file replaced under its old name kept the previous thumbnail —
                    // not until the cache dropped it, but effectively for good, because
                    // every hit refreshes its timestamp and the eviction order is exactly
                    // that timestamp. A listing that supplies neither value falls back to
                    // the old behaviour rather than to a key that changes on every pass.
                    val fingerprint = (item as? BrowserItem.File)
                        ?.takeIf { it.size > 0L || it.lastModified > 0L }
                        ?.let { ":${it.size}:${it.lastModified}" }
                        .orEmpty()

                    return@let Triple(type, path, "$type:$path$fingerprint")
                }

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

    PreviewCrossfade(bitmap, localFallbackIcon(item), selected, "LocalItemPreviewFade")
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

    PreviewCrossfade(bitmap, providerFallbackIcon(item), selected, "ProviderItemPreviewFade")
}

/**
 * Fades a generated preview in over the fallback icon.
 *
 * Both grids show the same thing while a preview is being produced and after it failed, and
 * the two of them are far enough apart in the file that the copies had already started to
 * differ in padding. What actually varies is the icon and the animation label.
 */
@Composable
private fun PreviewCrossfade(
    bitmap: ImageBitmap?,
    fallbackIcon: ImageVector,
    selected: Boolean,
    label: String
) {
    Crossfade(
        targetState = bitmap,
        animationSpec = tween(durationMillis = 300),
        label = label
    ) { currentBitmap ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                )
            } else {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    tint = previewIconColor(selected),
                    modifier = Modifier.size(54.dp)
                )
            }
        }
    }
}

@Composable
private fun previewIconColor(selected: Boolean): ComposeColor =
    if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

private fun localFallbackIcon(item: BrowserItem): ImageVector = when (item) {
    is BrowserItem.Storage -> when (item.volume.kind) {
        VolumeKind.INTERNAL -> Icons.Filled.Storage
        VolumeKind.REMOVABLE -> Icons.Filled.Usb
        VolumeKind.NETWORK -> Icons.Filled.Lan
    }
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
