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
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
        ) { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "TVFM-VideoThumbnail").apply { isDaemon = true }
        }
            .asCoroutineDispatcher()
    }

    private val imageDecodeDispatcher by lazy {
        ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "TVFM-ThumbnailDecode").apply { isDaemon = true }
        }
            .asCoroutineDispatcher()
    }

    /**
     * Decodes a preview without caching anything. Both the in-memory and the disk cache live
     * in [com.luckycatpaw.luckyfilestv.data.repository.ImageRepository] and
     * [GeneratedThumbnailCache]; this object is only ever called on a cache miss.
     */
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

    private fun decodeImageThumbnail(path: String, requestedSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(options, requestedSize, requestedSize)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeFile(path, decodeOptions)?.let {
            scaleBitmapToFit(it, requestedSize, requestedSize)
        }
    }

    private fun decodeVideoThumbnailBlocking(path: String, cancellationSignal: CancellationSignal): Bitmap? {
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

    private fun decodePdfThumbnail(path: String, maxWidth: Int = 384, maxHeight: Int = 240): Bitmap? = runCatching {
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

    private fun decodeAudioArtwork(path: String, requestedSize: Int = 384): Bitmap? {
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

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(options, requestedSize, requestedSize)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            decodeOptions
        )?.let {
            scaleBitmapToFit(it, requestedSize, requestedSize)
        }
    }

    private fun decodeApkIcon(context: Context, path: String, requestedSize: Int = 192): Bitmap? = runCatching {
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

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Locates the artwork of [directoryPath], or null when there is none.
     *
     * This used to fall back to a full `Files.newDirectoryStream` scan whenever `folder.jpg`
     * was missing, in order to catch differently cased names. That scan ran for every
     * coverless folder scrolling into view and could not be cached: the thumbnail caches are
     * keyed on the cover file, which does not exist in exactly this case. A media library of
     * season folders therefore re-listed every one of them on every pass through the grid.
     *
     * Instead a handful of known spellings are probed with a plain stat each, and folders
     * without a cover are remembered for a while so repeated scrolling costs nothing.
     */
    fun findFolderCover(directoryPath: String): String? {
        val cachedMiss = coverMisses[directoryPath]

        if (cachedMiss != null) {
            if (cachedMiss > SystemClock.elapsedRealtime()) return null
            coverMisses.remove(directoryPath, cachedMiss)
        }

        val directory = File(directoryPath)

        for (candidate in COVER_FILE_NAMES) {
            val cover = File(directory, candidate)
            if (cover.isFile) return cover.absolutePath
        }

        if (coverMisses.size >= MAX_COVER_MISSES) {
            coverMisses.clear()
        }

        coverMisses[directoryPath] = SystemClock.elapsedRealtime() + COVER_MISS_TTL_MILLIS
        return null
    }

    /** Spellings probed by [findFolderCover], in order. One stat(2) each. */
    private val COVER_FILE_NAMES = listOf("folder.jpg", "Folder.jpg", "folder.JPG")

    /** Directories known to have no cover, with the point in time the entry goes stale. */
    private val coverMisses = ConcurrentHashMap<String, Long>()

    private const val COVER_MISS_TTL_MILLIS = 60_000L
    private const val MAX_COVER_MISSES = 512
}
