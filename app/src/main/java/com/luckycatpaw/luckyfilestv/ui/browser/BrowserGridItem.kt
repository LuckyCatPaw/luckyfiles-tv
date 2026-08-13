package com.luckycatpaw.luckyfilestv.ui.browser

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.repository.ImageRepository
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.util.FileNameOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val videoThumbnailDispatcher by lazy {
    ThreadPoolExecutor(
        VIDEO_THUMBNAIL_THREADS,
        VIDEO_THUMBNAIL_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(VIDEO_THUMBNAIL_QUEUE_SIZE),
        { runnable ->
            Thread(runnable, "TVFM-VideoThumbnail").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    ).asCoroutineDispatcher()
}

private enum class LocalPreviewType(
    val cachePrefix: String
) {
    FOLDER("folder"),
    IMAGE("image"),
    VIDEO("video"),
    PDF("pdf"),
    AUDIO("audio"),
    APK("apk")
}

private data class LocalPreviewRequest(
    val type: LocalPreviewType,
    val path: String,
    val cacheKey: String
)

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
                onFocused = {
                    onFocused(item)
                },
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
                if (
                    optimizeFileNames &&
                    !item.document.isDirectory
                ) {
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

    val primaryText = if (lineBreakIndex >= 0) {
        displayName.substring(0, lineBreakIndex)
    } else {
        displayName
    }

    val secondLine = if (
        lineBreakIndex >= 0 &&
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
            .background(
                backgroundColor,
                shape
            )
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocused()
                }
            }
            .clickable {
                onClick()
            }
            .padding(
                horizontal = 10.dp,
                vertical = 10.dp
            )
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

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MarqueeNameLine(
                        text = primaryText,
                        selected = selected,
                        color = contentColor,
                        fontSize = 15
                    )

                    if (!secondLine.isNullOrBlank()) {
                        Spacer(
                            modifier = Modifier.height(2.dp)
                        )

                        MarqueeNameLine(
                            text = secondLine,
                            selected = selected,
                            color = contentColor,
                            fontSize = 14
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeNameLine(
    text: String,
    selected: Boolean,
    color: Color,
    fontSize: Int
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        lineHeight = 18.sp,
        fontWeight = if (selected) {
            FontWeight.Medium
        } else {
            FontWeight.Normal
        },
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = if (selected) {
            TextOverflow.Clip
        } else {
            TextOverflow.Ellipsis
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 700
                    )
                } else {
                    Modifier
                }
            )
    )
}

@Composable
private fun LocalItemPreview(
    item: BrowserItem,
    selected: Boolean,
    useFolderJpgAsIcon: Boolean
) {
    val context = LocalContext.current
    val imageRepository = ImageRepository.get(context)

    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = item.path,
        key2 = when (item) {
            is BrowserItem.Folder -> useFolderJpgAsIcon
            else -> null
        }
    ) {
        val previewRequest = withContext(Dispatchers.IO) {
            resolvePreviewRequest(
                item = item,
                useFolderJpgAsIcon = useFolderJpgAsIcon
            )
        }

        if (previewRequest == null) {
            value = null
            return@produceState
        }

        val generated = imageRepository.getLocalThumbnail(
            key = previewRequest.cacheKey
        ) {
            when (previewRequest.type) {
                LocalPreviewType.FOLDER,
                LocalPreviewType.IMAGE -> {
                    decodeImageThumbnail(
                        path = previewRequest.path,
                        requestedSize = 384
                    )
                }

                LocalPreviewType.VIDEO ->
                    decodeVideoThumbnail(previewRequest.path)

                LocalPreviewType.PDF ->
                    decodePdfThumbnail(previewRequest.path)

                LocalPreviewType.AUDIO ->
                    decodeAudioArtwork(previewRequest.path)

                LocalPreviewType.APK ->
                    decodeApkIcon(
                        context = context,
                        path = previewRequest.path
                    )
            }
        }

        value = generated?.asImageBitmap()
    }

    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        )

        return
    }

    Icon(
        imageVector = localFallbackIcon(item),
        contentDescription = null,
        tint = previewIconColor(selected),
        modifier = Modifier.size(54.dp)
    )
}

@Composable
private fun ProviderItemPreview(
    item: PickerBrowserItem,
    selected: Boolean
) {
    val context = LocalContext.current
    val imageRepository = ImageRepository.get(context)

    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = item.key,
        // Provider keys are stable across content changes. Keying the effect by
        // the complete immutable item makes metadata changes reload the image.
        key2 = item
    ) {
        value = when (item) {
            is PickerBrowserItem.ProviderRoot -> {
                imageRepository
                    .getRootIcon(
                        item.root
                    )
                    ?.asImageBitmap()
            }

            is PickerBrowserItem.ProviderDocument -> {
                val thumbnail =
                    imageRepository
                        .getProviderThumbnail(
                            item.document
                        )

                if (thumbnail != null) {
                    thumbnail.asImageBitmap()
                } else {
                    imageRepository
                        .getProviderIcon(
                            item.document
                        )
                        ?.asImageBitmap()
                }
            }

            is PickerBrowserItem.Local ->
                null
        }
    }

    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        )

        return
    }

    Icon(
        imageVector = providerFallbackIcon(item),
        contentDescription = null,
        tint = previewIconColor(selected),
        modifier = Modifier.size(54.dp)
    )
}

@Composable
private fun previewIconColor(
    selected: Boolean
): Color {
    return if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun localFallbackIcon(
    item: BrowserItem
): ImageVector {
    return when (item) {
        is BrowserItem.Storage ->
            Icons.Filled.Storage

        is BrowserItem.Folder ->
            Icons.Filled.Folder

        is BrowserItem.File ->
            fileIconForExtension(
                File(item.path)
                    .extension
                    .lowercase()
            )
    }
}

private fun providerFallbackIcon(
    item: PickerBrowserItem
): ImageVector {
    return when (item) {
        is PickerBrowserItem.ProviderRoot ->
            Icons.Filled.Storage

        is PickerBrowserItem.ProviderDocument -> {
            when {
                item.document.isDirectory ->
                    Icons.Filled.Folder

                item.document.mimeType.startsWith(
                    "image/",
                    ignoreCase = true
                ) ->
                    Icons.Filled.Image

                item.document.mimeType.startsWith(
                    "video/",
                    ignoreCase = true
                ) ->
                    Icons.Filled.Movie

                item.document.mimeType.startsWith(
                    "audio/",
                    ignoreCase = true
                ) ->
                    Icons.Filled.Audiotrack

                else ->
                    Icons.Filled.InsertDriveFile
            }
        }

        is PickerBrowserItem.Local ->
            localFallbackIcon(
                item.item
            )
    }
}

private fun fileIconForExtension(
    extension: String
): ImageVector {
    return when {
        extension in IMAGE_EXTENSIONS ->
            Icons.Filled.Image

        extension in VIDEO_EXTENSIONS ->
            Icons.Filled.Movie

        extension in AUDIO_EXTENSIONS ->
            Icons.Filled.Audiotrack

        else ->
            Icons.Filled.InsertDriveFile
    }
}

private fun resolvePreviewRequest(
    item: BrowserItem,
    useFolderJpgAsIcon: Boolean
): LocalPreviewRequest? {
    val typeAndPath = when (item) {
        is BrowserItem.Folder -> {
            if (!useFolderJpgAsIcon) {
                return null
            }

            LocalPreviewType.FOLDER to
                    (findFolderCover(item.path) ?: return null)
        }

        is BrowserItem.File -> {
            val type = previewTypeForExtension(
                File(item.path).extension.lowercase()
            ) ?: return null

            type to item.path
        }

        is BrowserItem.Storage -> return null
    }

    val (type, path) = typeAndPath

    return LocalPreviewRequest(
        type = type,
        path = path,
        cacheKey = createCacheKey(
            prefix = type.cachePrefix,
            path = path
        )
    )
}

private fun previewTypeForExtension(
    extension: String
): LocalPreviewType? {
    return when {
        extension in IMAGE_EXTENSIONS -> LocalPreviewType.IMAGE
        extension in VIDEO_EXTENSIONS -> LocalPreviewType.VIDEO
        extension == "pdf" -> LocalPreviewType.PDF
        extension in AUDIO_EXTENSIONS -> LocalPreviewType.AUDIO
        extension == "apk" -> LocalPreviewType.APK
        else -> null
    }
}

private fun decodeImageThumbnail(
    path: String,
    requestedSize: Int
): Bitmap? {
    val bounds =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

    BitmapFactory.decodeFile(
        path,
        bounds
    )

    if (
        bounds.outWidth <= 0 ||
        bounds.outHeight <= 0
    ) {
        return null
    }

    var sampleSize = 1

    while (
        bounds.outWidth / sampleSize >
        requestedSize * 2 ||
        bounds.outHeight / sampleSize >
        requestedSize * 2
    ) {
        sampleSize *= 2
    }

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

    val decoded = BitmapFactory.decodeFile(
        path,
        options
    ) ?: return null

    return scaleBitmapToFit(
        bitmap = decoded,
        maxWidth = requestedSize,
        maxHeight = requestedSize
    )
}

private fun decodePdfThumbnail(
    path: String,
    maxWidth: Int = 384,
    maxHeight: Int = 240
): Bitmap? {
    return runCatching {
        val descriptor = ParcelFileDescriptor.open(
            File(path),
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        try {
            val renderer = PdfRenderer(descriptor)

            try {
                if (renderer.pageCount <= 0) {
                    null
                } else {
                    val page = renderer.openPage(0)

                    try {
                        val scale = minOf(
                            maxWidth.toFloat() / page.width.toFloat(),
                            maxHeight.toFloat() / page.height.toFloat()
                        )
                        val width = (page.width * scale)
                            .roundToInt()
                            .coerceAtLeast(1)
                        val height = (page.height * scale)
                            .roundToInt()
                            .coerceAtLeast(1)
                        val bitmap = createBitmap(
                            width,
                            height,
                            Bitmap.Config.ARGB_8888
                        )

                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        bitmap
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        } finally {
            descriptor.close()
        }
    }.getOrNull()
}

private fun decodeAudioArtwork(
    path: String,
    requestedSize: Int = 384
): Bitmap? {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(path)
        val artwork = retriever.embeddedPicture ?: return null
        decodeImageBytesThumbnail(
            bytes = artwork,
            requestedSize = requestedSize
        )
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun decodeImageBytesThumbnail(
    bytes: ByteArray,
    requestedSize: Int
): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        bounds
    )

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sampleSize = 1

    while (
        bounds.outWidth / sampleSize > requestedSize * 2 ||
        bounds.outHeight / sampleSize > requestedSize * 2
    ) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    }

    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        options
    ) ?: return null

    return scaleBitmapToFit(
        bitmap = decoded,
        maxWidth = requestedSize,
        maxHeight = requestedSize
    )
}

private fun decodeApkIcon(
    context: Context,
    path: String,
    requestedSize: Int = 192
): Bitmap? {
    return runCatching {
        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, 0)
        } ?: return@runCatching null

        val applicationInfo = packageInfo.applicationInfo
            ?: return@runCatching null
        applicationInfo.sourceDir = path
        applicationInfo.publicSourceDir = path

        drawableToBitmap(
            drawable = applicationInfo.loadIcon(packageManager),
            maxSize = requestedSize
        )
    }.getOrNull()
}

private fun drawableToBitmap(
    drawable: Drawable,
    maxSize: Int
): Bitmap {
    if (drawable is BitmapDrawable) {
        val source = drawable.bitmap

        if (source != null) {
            if (source.width <= maxSize && source.height <= maxSize) {
                return source
            }

            val scale = minOf(
                maxSize.toFloat() / source.width.toFloat(),
                maxSize.toFloat() / source.height.toFloat()
            )

            return source.scale(
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        }
    }

    val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1)
    val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1)
    val scale = minOf(
        maxSize.toFloat() / intrinsicWidth.toFloat(),
        maxSize.toFloat() / intrinsicHeight.toFloat(),
        1f
    )
    val width = (intrinsicWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (intrinsicHeight * scale).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(
        width,
        height,
        Bitmap.Config.ARGB_8888
    )

    drawable.setBounds(0, 0, width, height)
    drawable.draw(Canvas(bitmap))
    return bitmap
}

private suspend fun decodeVideoThumbnail(
    path: String
): Bitmap? {
    return try {
        withTimeoutOrNull(VIDEO_THUMBNAIL_TIMEOUT_MS) {
            val cancellationSignal = CancellationSignal()
            val cancellationHandle =
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) {
                        cancellationSignal.cancel()
                    }
                }

            try {
                runInterruptible(videoThumbnailDispatcher) {
                    decodeVideoThumbnailBlocking(
                        path = path,
                        cancellationSignal = cancellationSignal
                    )
                }
            } finally {
                cancellationHandle?.dispose()
                cancellationSignal.cancel()
            }
        }
    } catch (_: RejectedExecutionException) {
        null
    }
}

private fun decodeVideoThumbnailBlocking(
    path: String,
    cancellationSignal: CancellationSignal
): Bitmap? {
    if (Thread.currentThread().isInterrupted) return null
    val file = File(path)
    if (!file.isFile || !file.canRead()) return null

    val thumbnail =
        try {
            ThumbnailUtils.createVideoThumbnail(
                file,
                Size(
                    VIDEO_THUMBNAIL_WIDTH,
                    VIDEO_THUMBNAIL_HEIGHT
                ),
                cancellationSignal
            )
        } catch (_: Exception) {
            null
        }

    if (thumbnail != null) return thumbnail
    cancellationSignal.throwIfCanceled()

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        cancellationSignal.throwIfCanceled()

        val durationMs =
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

        val frameAtTenPercent =
            if (durationMs > 0L) {
                retriever.getFrameAtTime(
                    durationMs * VIDEO_THUMBNAIL_POSITION_US_PER_MS,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } else {
                null
            }

        cancellationSignal.throwIfCanceled()
        val frame = frameAtTenPercent ?: retriever.frameAtTime

        frame?.let {
            scaleBitmapToFit(
                bitmap = it,
                maxWidth = VIDEO_THUMBNAIL_WIDTH,
                maxHeight = VIDEO_THUMBNAIL_HEIGHT
            )
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching {
            retriever.release()
        }
    }
}

private fun scaleBitmapToFit(
    bitmap: Bitmap,
    maxWidth: Int,
    maxHeight: Int
): Bitmap {
    if (
        bitmap.width <= 0 ||
        bitmap.height <= 0
    ) {
        return bitmap
    }

    val widthScale =
        maxWidth.toFloat() /
                bitmap.width.toFloat()

    val heightScale =
        maxHeight.toFloat() /
                bitmap.height.toFloat()

    val scale =
        minOf(
            widthScale,
            heightScale,
            1f
        )

    if (scale >= 1f) {
        return bitmap
    }

    val targetWidth =
        (bitmap.width * scale)
            .roundToInt()
            .coerceAtLeast(1)

    val targetHeight =
        (bitmap.height * scale)
            .roundToInt()
            .coerceAtLeast(1)

    val scaled = bitmap.scale(
            targetWidth,
            targetHeight,
            true
        )

    if (
        scaled !== bitmap &&
        !bitmap.isRecycled
    ) {
        bitmap.recycle()
    }

    return scaled
}

private fun createCacheKey(
    prefix: String,
    path: String
): String {
    val file = File(path)

    return buildString {
        append(prefix)
        append(':')
        append(file.absolutePath)
        append(':')
        append(file.length())
        append(':')
        append(file.lastModified())
    }
}

private fun findFolderCover(
    directoryPath: String
): String? {
    val directory = File(directoryPath)
    val conventionalCover = File(directory, "folder.jpg")

    if (conventionalCover.isFile) {
        return conventionalCover.absolutePath
    }

    return runCatching {
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            entries.firstOrNull { path ->
                Files.isRegularFile(path) &&
                        path.fileName.toString().equals("folder.jpg", ignoreCase = true)
            }?.toFile()?.absolutePath
        }
    }.getOrNull()
}

private val VIDEO_EXTENSIONS = setOf(
    "avi",
    "mkv",
    "mp4",
    "m4v",
    "mov",
    "webm",
    "mpeg",
    "mpg",
    "ts",
    "m2ts",
    "wmv",
    "flv",
    "vob"
)

private val IMAGE_EXTENSIONS = setOf(
    "jpg",
    "jpeg",
    "png",
    "webp",
    "gif",
    "bmp",
    "heic",
    "heif",
    "avif"
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3",
    "m4a",
    "mka",
    "aac",
    "flac",
    "ogg",
    "oga",
    "opus",
    "wav",
    "wma",
    "ape",
    "alac",
    "ac3",
    "dts"
)

private const val VIDEO_THUMBNAIL_THREADS = 2
private const val VIDEO_THUMBNAIL_QUEUE_SIZE = 12
private const val VIDEO_THUMBNAIL_TIMEOUT_MS = 15_000L
private const val VIDEO_THUMBNAIL_WIDTH = 384
private const val VIDEO_THUMBNAIL_HEIGHT = 240
private const val VIDEO_THUMBNAIL_POSITION_US_PER_MS = 100L
