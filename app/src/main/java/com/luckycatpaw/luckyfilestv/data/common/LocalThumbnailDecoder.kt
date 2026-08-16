package com.luckycatpaw.luckyfilestv.data.common

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
import android.util.Log
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object LocalThumbnailDecoder {
    private const val TAG = "LocalThumbnailDecoder"

    private val videoThumbnailDispatcher by lazy {
        ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { runnable -> Thread(runnable, "TVFM-VideoThumbnail").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    private val imageDecodeDispatcher by lazy {
        ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { runnable -> Thread(runnable, "TVFM-ThumbnailDecode").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    suspend fun decode(context: Context, type: String, path: String): Bitmap? {
        if (type == "video") return decodeVideoThumbnail(path)

        return withContext(imageDecodeDispatcher) {
            when (type) {
                "folder", "image" -> decodeImageThumbnail(path, 384)
                "pdf" -> decodePdfThumbnail(path)
                "audio" -> decodeAudioArtwork(path)
                "apk" -> decodeApkIcon(context, path)
                else -> null
            }
        }
    }

    fun previewTypeForExtension(extension: String): String? = when (extension) {
        in MimeTypes.IMAGE_EXTENSIONS -> "image"
        in MimeTypes.VIDEO_EXTENSIONS -> "video"
        "pdf" -> "pdf"
        in MimeTypes.AUDIO_EXTENSIONS -> "audio"
        "apk" -> "apk"
        else -> null
    }

    fun decodeImageThumbnail(path: String, requestedSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        var sampleSize = 1
        while (options.outWidth / sampleSize > requestedSize * 2 ||
            options.outHeight / sampleSize > requestedSize * 2
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })?.let {
            scaleBitmapToFit(it, requestedSize, requestedSize)
        }
    }

    fun decodeVideoThumbnailBlocking(path: String, cancellationSignal: CancellationSignal): Bitmap? {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return decodeVideoWithAndroid(file, cancellationSignal)
    }

    private suspend fun decodeVideoThumbnail(path: String): Bitmap? {
        val signal = CancellationSignal()
        return try {
            withTimeoutOrNull(15_000.milliseconds) {
                runInterruptible(videoThumbnailDispatcher) {
                    decodeVideoThumbnailBlocking(path, signal)
                }
            }
        } finally {
            signal.cancel()
        }
    }

    private fun decodeVideoWithAndroid(file: File, signal: CancellationSignal?): Bitmap? = try {
        ThumbnailUtils.createVideoThumbnail(file, Size(384, 240), signal)
    } catch (e: Exception) {
        Log.e(TAG, "Android ThumbnailUtils failed for ${file.name}", e)
        null
    }

    fun decodePdfThumbnail(path: String, maxWidth: Int = 384, maxHeight: Int = 240): Bitmap? = runCatching {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return@runCatching null
                renderer.openPage(0).use { page ->
                    val scale = minOf(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height)
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    fun decodeAudioArtwork(path: String, requestedSize: Int = 384): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture?.let { decodeImageBytesThumbnail(it, requestedSize) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeImageBytesThumbnail(bytes: ByteArray, requestedSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        var sampleSize = 1
        while (options.outWidth / sampleSize > requestedSize * 2 ||
            options.outHeight / sampleSize > requestedSize * 2
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize =
                    sampleSize
            }
        )?.let {
            scaleBitmapToFit(it, requestedSize, requestedSize)
        }
    }

    fun decodeApkIcon(context: Context, path: String, requestedSize: Int = 192): Bitmap? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        } ?: return null

        info.applicationInfo?.apply {
            sourceDir = path
            publicSourceDir = path
        }?.loadIcon(pm)?.let { drawableToBitmap(it, requestedSize) }
    }.getOrNull()

    private fun drawableToBitmap(drawable: Drawable, maxSize: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            drawable.bitmap?.let { source ->
                if (source.width <= maxSize && source.height <= maxSize) return source
                val scale = minOf(maxSize.toFloat() / source.width, maxSize.toFloat() / source.height)
                return source.scale(
                    (source.width * scale).roundToInt().coerceAtLeast(1),
                    (source.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            }
        }
        val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1)
        val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(maxSize.toFloat() / intrinsicWidth, maxSize.toFloat() / intrinsicHeight, 1f)
        val width = (intrinsicWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).roundToInt().coerceAtLeast(1)
        return createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(this))
        }
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap

        return bitmap.scale(
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        ).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    fun findFolderCover(directoryPath: String): String? {
        val directory = File(directoryPath)
        val conventionalCover = File(directory, "folder.jpg")
        if (conventionalCover.isFile) return conventionalCover.absolutePath

        return runCatching {
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                entries.firstOrNull { path ->
                    Files.isRegularFile(path) && path.fileName.toString().equals("folder.jpg", ignoreCase = true)
                }?.toFile()?.absolutePath
            }
        }.getOrNull()
    }
}
