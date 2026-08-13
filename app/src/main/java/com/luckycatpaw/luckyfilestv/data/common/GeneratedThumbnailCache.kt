package com.luckycatpaw.luckyfilestv.data.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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

class GeneratedThumbnailCache private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val appVersionCode = runCatching {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .longVersionCode
    }.getOrDefault(0L)

    private val baseDirectory = File(
        appContext.cacheDir,
        CACHE_ROOT_DIRECTORY
    )

    private val currentDirectory = File(
        baseDirectory,
        "app_${appVersionCode}_schema_$CACHE_SCHEMA_VERSION"
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
            deleteOldAppVersionCaches()
            pruneDiskCache()
        }
    }

    suspend fun getOrCreate(
        key: String,
        generator: suspend () -> Bitmap?
    ): Bitmap? {
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

    private fun readFromDisk(
        hashedKey: String
    ): Bitmap? {
        val file = cacheFile(hashedKey)

        if (!file.isFile) {
            return null
        }

        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()

        if (bitmap == null) {
            file.delete()
            return null
        }

        file.setLastModified(System.currentTimeMillis())
        return bitmap
    }

    private fun writeToDisk(
        hashedKey: String,
        bitmap: Bitmap
    ) {
        if (!currentDirectory.exists() && !currentDirectory.mkdirs()) {
            return
        }

        val target = cacheFile(hashedKey)

        if (target.isFile) {
            target.setLastModified(System.currentTimeMillis())
            return
        }

        val temporary = File(
            currentDirectory,
            ".$hashedKey.${System.nanoTime()}.tmp"
        )

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
                output.fd.sync()
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
            return
        }

        if (
            writesSinceMaintenance.incrementAndGet() >=
            MAINTENANCE_WRITE_INTERVAL
        ) {
            writesSinceMaintenance.set(0)
            try {
                maintenanceExecutor.execute {
                    pruneDiskCache()
                }
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    private fun scheduleDiskWrite(
        hashedKey: String,
        bitmap: Bitmap
    ) {
        if (!pendingDiskWrites.add(hashedKey)) {
            return
        }

        try {
            maintenanceExecutor.execute {
                try {
                    writeToDisk(
                        hashedKey = hashedKey,
                        bitmap = bitmap
                    )
                } finally {
                    pendingDiskWrites.remove(hashedKey)
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingDiskWrites.remove(hashedKey)
        }
    }

    private fun cacheFile(
        hashedKey: String
    ): File {
        return File(
            currentDirectory,
            "$hashedKey.jpg"
        )
    }

    private fun deleteOldAppVersionCaches() {
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

        if (totalBytes <= MAX_DISK_CACHE_BYTES) {
            return
        }

        for (file in files) {
            if (totalBytes <= TARGET_DISK_CACHE_BYTES) {
                break
            }

            val length = file.length()

            if (file.delete()) {
                totalBytes -= length
            }
        }
    }

    private fun hashKey(
        key: String
    ): String {
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
        private const val MAX_DISK_CACHE_BYTES = 512L * 1024L * 1024L
        private const val TARGET_DISK_CACHE_BYTES = 448L * 1024L * 1024L

        @Volatile
        private var instance: GeneratedThumbnailCache? = null

        fun get(context: Context): GeneratedThumbnailCache {
            return instance ?: synchronized(this) {
                instance ?: GeneratedThumbnailCache(context).also {
                    instance = it
                }
            }
        }
    }
}
