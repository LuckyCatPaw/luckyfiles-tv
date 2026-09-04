package com.luckycatpaw.luckyfilestv.data.common

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.luckycatpaw.luckyfilestv.data.provider.FileContentProvider
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
     * Previews from a source whose metadata is expensive, limited to a few at a time.
     *
     * A grid full of videos would otherwise open dozens of connections at once and stall the
     * share for everything else, including playback.
     */
    private val expensivePermits = Semaphore(2)

    @Volatile
    private var sources: FileSourceRegistry? = null

    /**
     * `true` when sizes, dates and previews cost nothing worth counting.
     *
     * Asks the source rather than the scheme: whether a preview is cheap is a property of
     * where the file lives, and a future source may well be remote and cheap, or local and
     * slow. Unknown locations count as cheap so a lookup failure never blocks a preview.
     */
    fun hasCheapMetadata(context: Context, path: String): Boolean {
        val location = SourcePath.parseOrNull(path) ?: return true

        return runCatching {
            sources(context).source(location).capabilities.cheapMetadata
        }.getOrDefault(true)
    }

    private fun sources(context: Context): FileSourceRegistry = sources ?: synchronized(this) {
        sources ?: FileSourceRegistry.create(context.applicationContext).also { sources = it }
    }

    /**
     * Decodes a preview without caching anything. Both the in-memory and the disk cache live
     * in [com.luckycatpaw.luckyfilestv.data.repository.ImageRepository] and
     * [GeneratedThumbnailCache]; this object is only ever called on a cache miss.
     */
    /**
     * Decodes a preview, wherever the file lives.
     *
     * One path for every source. The decoders read through the app's own content provider,
     * which hands out a plain descriptor for a local file and a proxy for a remote one — so
     * a video on a share is seeked into rather than downloaded, and both end up choosing
     * their frame the same way. An APK is the exception: its icon needs an installable path.
     */
    suspend fun decode(context: Context, type: String, path: String): Bitmap? {
        if (type == "apk") {
            return withContext(imageDecodeDispatcher) { decodeApkIcon(context, path) }
        }

        // How to read is a question of the scheme, how many at once one of the source: a
        // cheap source needs no queue in front of it.
        return if (hasCheapMetadata(context, path)) {
            decodeFrom(context, type, path)
        } else {
            expensivePermits.withPermit { decodeFrom(context, type, path) }
        }
    }

    private suspend fun decodeFrom(context: Context, type: String, path: String): Bitmap? {
        val uri = FileContentProvider.createUri(context, path)

        return if (type == "video") {
            decodeVideoThumbnail(context, uri)
        } else {
            withContext(imageDecodeDispatcher) {
                when (type) {
                    "folder", "image" -> decodeImageThumbnail(context, uri, 384)
                    "pdf" -> decodePdfThumbnail(context, uri)
                    "audio" -> decodeAudioArtwork(context, uri)
                    else -> null
                }
            }
        }
    }

    private suspend fun decodeVideoThumbnail(context: Context, uri: Uri): Bitmap? =
        withTimeoutOrNull(20_000.milliseconds) {
            runInterruptible(videoThumbnailDispatcher) {
                val retriever = MediaMetadataRetriever()

                try {
                    retriever.setDataSource(context, uri)
                    representativeFrame(retriever)?.let { scaleBitmapToFit(it, 384, 240) }
                } catch (error: Exception) {
                    Log.w(TAG, "Video thumbnail failed for a share", error)
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }

    /**
     * Picks a frame that actually shows something.
     *
     * The first frame of a film is usually black — a fade in, a title card, a leader — so
     * the search starts a tenth of the way in and moves further if what comes back is dark.
     * `ThumbnailUtils` used to do this for local files and nothing did it for shares, which
     * is exactly why previews there came out black.
     */
    private fun representativeFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L

        val offsetsUs = if (durationMs > 0L) {
            listOf(durationMs / 10, durationMs / 3, durationMs / 2).map { it * 1000L }
        } else {
            // No duration in the metadata, which happens with damaged or streamed files.
            listOf(10_000_000L, 60_000_000L)
        }

        offsetsUs.forEach { timeUs ->
            val frame = frameAt(retriever, timeUs)
            if (frame != null && !frame.isMostlyBlack()) return frame
        }

        // Everything dark, or seeking failed: better the first frame than no preview.
        return frameAt(retriever, 0L)
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? = runCatching {
        // Scales while decoding, which saves a full size bitmap per preview.
        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 240)
    }.getOrNull() ?: runCatching {
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }.getOrNull()

    /** Average brightness below this counts as an unusable frame. */
    private const val BLACK_THRESHOLD = 12

    /** Samples a grid instead of every pixel: enough to tell a black frame from an image. */
    private fun Bitmap.isMostlyBlack(): Boolean {
        val steps = 8
        var total = 0L

        for (x in 0 until steps) {
            for (y in 0 until steps) {
                val pixel = getPixel(
                    (width - 1) * x / (steps - 1),
                    (height - 1) * y / (steps - 1)
                )

                total += android.graphics.Color.red(pixel) +
                    android.graphics.Color.green(pixel) +
                    android.graphics.Color.blue(pixel)
            }
        }

        return total / (steps * steps * 3) < BLACK_THRESHOLD
    }

    private fun decodeImageThumbnail(context: Context, uri: Uri, requestedSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        // Two passes, two reads. Measuring only touches the header, so the seek in the
        // proxy descriptor keeps this to a fraction of the file.
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(options, requestedSize, requestedSize)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        }.getOrNull()?.let { scaleBitmapToFit(it, requestedSize, requestedSize) }
    }

    private fun decodePdfThumbnail(context: Context, uri: Uri, maxWidth: Int = 384, maxHeight: Int = 240): Bitmap? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                renderFirstPdfPage(fd, maxWidth, maxHeight)
            }
        }.getOrNull()

    private fun decodeAudioArtwork(context: Context, uri: Uri, requestedSize: Int = 384): Bitmap? {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { decodeImageBytesThumbnail(it, requestedSize) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
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

    private fun renderFirstPdfPage(descriptor: ParcelFileDescriptor, maxWidth: Int, maxHeight: Int): Bitmap? =
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount <= 0) return null

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
