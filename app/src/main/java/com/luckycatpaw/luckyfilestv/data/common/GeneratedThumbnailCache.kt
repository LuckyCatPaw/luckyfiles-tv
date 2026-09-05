package com.luckycatpaw.luckyfilestv.data.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.storage.StorageManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Persistent thumbnail cache on top of [Context.getCacheDir].
 *
 * Deliberately holds no bitmaps in memory: the single in-memory LRU of the app lives in
 * [com.luckycatpaw.luckyfilestv.data.repository.ImageRepository], which is the only caller
 * of this class. Every entry reaching this point is therefore already a memory cache miss.
 */
class GeneratedThumbnailCache private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val baseDirectory = File(
        appContext.cacheDir,
        CACHE_ROOT_DIRECTORY
    )

    /**
     * Named after the cache format, not after the app version.
     *
     * It used to carry the version code as well, which meant every update threw the whole
     * cache away and a large library re-decoded every video frame it had already produced.
     * A new build does not change what a thumbnail looks like — [CACHE_SCHEMA_VERSION] does,
     * and it has to be raised by hand whenever the generation or the encoding changes.
     */
    private val currentDirectory = File(
        baseDirectory,
        "schema_$CACHE_SCHEMA_VERSION"
    )

    private val maintenanceThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "TVFM-ThumbnailCache").apply {
            isDaemon = true
        }
    }

    private val maintenanceExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_DISK_WRITES),
        maintenanceThreadFactory
    )

    private val pendingDiskWrites = ConcurrentHashMap.newKeySet<String>()
    private val writesSinceMaintenance = AtomicInteger(0)

    init {
        currentDirectory.mkdirs()
        maintenanceExecutor.execute {
            deleteOutdatedCaches()
            pruneDiskCache()
        }
    }

    suspend fun getOrCreate(key: String, generator: suspend () -> Bitmap?): Bitmap? {
        val hashedKey = hashKey(key)

        val diskBitmap = withContext(Dispatchers.IO) {
            readFromDisk(hashedKey)
        }

        if (diskBitmap != null) {
            return diskBitmap
        }

        currentCoroutineContext().ensureActive()
        val generated = generator() ?: return null

        currentCoroutineContext().ensureActive()

        scheduleDiskWrite(
            hashedKey = hashedKey,
            bitmap = generated
        )

        return generated
    }

    private fun readFromDisk(hashedKey: String): Bitmap? {
        val file = cacheFile(hashedKey)

        if (!file.isFile) {
            return null
        }

        // The cache files are opaque JPEGs and the result goes straight into the single
        // in-memory LRU, so decoding them as ARGB_8888 would double their footprint there
        // compared to a freshly generated thumbnail.
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()

        if (bitmap == null) {
            file.delete()
            return null
        }

        touch(file)
        return bitmap
    }

    /**
     * Marks an entry as recently used, at most once per [TOUCH_INTERVAL_MILLIS].
     *
     * The timestamp is the only thing [pruneDiskCache] sorts on, so it has to be kept up to
     * date — but writing it on every hit meant a metadata write per tile while the grid was
     * being scrolled. The interval is far shorter than the time between two prunes, so the
     * order they are evicted in does not change; what disappears is the write.
     *
     * A stamp in the future comes from a clock that has since been corrected and would
     * otherwise survive every prune, so it is pulled back to now.
     */
    private fun touch(file: File) {
        val now = System.currentTimeMillis()
        val stamped = file.lastModified()

        if (stamped in (now - TOUCH_INTERVAL_MILLIS)..now) return

        file.setLastModified(now)
    }

    /**
     * @return `true` when enough entries were written that the size limit is worth checking
     *   again. Deliberately reported rather than acted on: the caller already owns the
     *   maintenance thread, so pruning from there needs no second task and cannot be turned
     *   away by a full queue.
     */
    private fun writeToDisk(hashedKey: String, bitmap: Bitmap): Boolean {
        if (!currentDirectory.exists() && !currentDirectory.mkdirs()) {
            return false
        }

        val target = cacheFile(hashedKey)

        if (target.isFile) {
            touch(target)
            return false
        }

        val temporary = File(
            currentDirectory,
            ".$hashedKey.${System.nanoTime()}.tmp"
        )

        reserveDiskSpace(THUMBNAIL_RESERVE_BYTES_ESTIMATE)

        val encodableBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            ) ?: bitmap
        } else {
            bitmap
        }

        val success = runCatching {
            FileOutputStream(temporary).use { output ->
                val compressed = encodableBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    JPEG_QUALITY,
                    output
                )

                if (!compressed) {
                    error("Bitmap could not be compressed.")
                }

                output.flush()
            }

            if (target.exists()) {
                target.delete()
            }

            if (!temporary.renameTo(target)) {
                temporary.copyTo(
                    target = target,
                    overwrite = true
                )
                temporary.delete()
            }

            target.setLastModified(System.currentTimeMillis())
            true
        }.getOrElse { error ->
            Log.w(
                LOG_TAG,
                "Thumbnail could not be saved to disk cache: ${error.message}"
            )
            false
        }

        if (encodableBitmap !== bitmap && !encodableBitmap.isRecycled) {
            encodableBitmap.recycle()
        }

        if (!success) {
            temporary.delete()
            return false
        }

        return writesSinceMaintenance.incrementAndGet() >= MAINTENANCE_WRITE_INTERVAL
    }

    private fun scheduleDiskWrite(hashedKey: String, bitmap: Bitmap) {
        if (!pendingDiskWrites.add(hashedKey)) {
            return
        }

        try {
            maintenanceExecutor.execute {
                val maintenanceDue = try {
                    writeToDisk(
                        hashedKey = hashedKey,
                        bitmap = bitmap
                    )
                } finally {
                    pendingDiskWrites.remove(hashedKey)
                }

                // In this task rather than in one of its own. The executor has a single
                // thread and a bounded queue, so a submitted prune would have run here
                // anyway — except while the queue was full, which is exactly when the cache
                // is growing fastest and the prune was silently dropped instead.
                if (maintenanceDue) {
                    writesSinceMaintenance.set(0)
                    pruneDiskCache()
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingDiskWrites.remove(hashedKey)
        }
    }

    private fun cacheFile(hashedKey: String): File = File(
        currentDirectory,
        "$hashedKey.jpg"
    )

    /**
     * Removes every cache directory that is not the current one.
     *
     * Two kinds end up here: a raised [CACHE_SCHEMA_VERSION], and the per-app-version
     * directories earlier builds created. The latter are swept up once, on the first start
     * after the update that stopped writing them.
     */
    private fun deleteOutdatedCaches() {
        if (!baseDirectory.exists()) {
            baseDirectory.mkdirs()
            return
        }

        baseDirectory.listFiles()
            ?.filter {
                it.isDirectory &&
                    it.absolutePath != currentDirectory.absolutePath
            }
            ?.forEach { directory ->
                runCatching {
                    directory.deleteRecursively()
                }
            }
    }

    private fun pruneDiskCache() {
        val files = currentDirectory.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.equals("jpg", ignoreCase = true)
            }
            ?.sortedBy {
                it.lastModified()
            }
            ?: return

        var totalBytes = files.sumOf {
            it.length()
        }

        val maxBytes = maxDiskCacheBytes()
        if (totalBytes <= maxBytes) {
            return
        }

        val targetBytes = (maxBytes * TARGET_CACHE_RATIO).toLong()

        for (file in files) {
            if (totalBytes <= targetBytes) {
                break
            }

            val length = file.length()

            if (file.delete()) {
                totalBytes -= length
            }
        }
    }

    private fun maxDiskCacheBytes(): Long {
        val allocatableBytes = allocatableBytesViaStorageManager()
        val usableBytes = allocatableBytes
            ?: runCatching { currentDirectory.usableSpace }.getOrDefault(0L)
        val budget = (usableBytes * CACHE_BUDGET_RATIO_OF_FREE_SPACE).toLong()
        return budget.coerceIn(MIN_DISK_CACHE_BYTES, MAX_DISK_CACHE_BYTES)
    }

    private fun allocatableBytesViaStorageManager(): Long? = runCatching {
        val storageManager = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val uuid = storageManager.getUuidForPath(currentDirectory)
        storageManager.getAllocatableBytes(uuid)
    }.getOrNull()

    private fun reserveDiskSpace(bytes: Long) {
        runCatching {
            val storageManager = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val uuid = storageManager.getUuidForPath(currentDirectory)
            storageManager.allocateBytes(uuid, bytes)
        }
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))

        val output = CharArray(digest.size * 2)

        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX_DIGITS[value ushr 4]
            output[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }

        return String(output)
    }

    companion object {
        private const val LOG_TAG = "TVFM-ThumbnailCache"
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val CACHE_ROOT_DIRECTORY = "generated_thumbnails"
        private const val CACHE_SCHEMA_VERSION = 1
        private const val JPEG_QUALITY = 88
        private const val MAX_PENDING_DISK_WRITES = 24
        private const val MAINTENANCE_WRITE_INTERVAL = 64
        private const val MIN_DISK_CACHE_BYTES = 32L * 1024L * 1024L
        private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L
        private const val CACHE_BUDGET_RATIO_OF_FREE_SPACE = 0.02
        private const val TARGET_CACHE_RATIO = 0.85
        private const val THUMBNAIL_RESERVE_BYTES_ESTIMATE = 512L * 1024L
        private const val TOUCH_INTERVAL_MILLIS = 12L * 60L * 60L * 1000L

        @Volatile
        private var instance: GeneratedThumbnailCache? = null

        fun get(context: Context): GeneratedThumbnailCache = instance ?: synchronized(this) {
            instance ?: GeneratedThumbnailCache(context).also {
                instance = it
            }
        }
    }
}
