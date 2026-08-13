package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo
import com.luckycatpaw.luckyfilestv.data.common.GeneratedThumbnailCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class ImageRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val providerVisualRepository = ProviderVisualRepository.get(appContext)
    private val generatedThumbnailCache = GeneratedThumbnailCache.get(appContext)

    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()
        .coerceIn(16 * 1024, 64 * 1024)

    private val memoryCache = object : LruCache<String, Bitmap>(maxMemoryKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val keyLocks = Array(32) { Mutex() }
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val globalLoadSemaphore = Semaphore(4)

    suspend fun getLocalThumbnail(
        key: String,
        generator: suspend () -> Bitmap?
    ): Bitmap? {
        return getOrCreate(key) {
            generatedThumbnailCache.getOrCreate(key, generator)
        }
    }

    suspend fun getProviderThumbnail(
        document: ProviderDocumentInfo,
        width: Int = 384,
        height: Int = 240
    ): Bitmap? {
        val key = "provider-thumb:${document.authority}:${document.documentId}:${document.lastModified ?: 0L}:${width}x$height"
        return getOrCreate(key) {
            providerVisualRepository.loadThumbnail(document, width, height)
        }
    }

    suspend fun getProviderIcon(
        document: ProviderDocumentInfo,
        size: Int = 128
    ): Bitmap? {
        val key = "provider-icon:${document.authority}:${document.iconResId}:$size"
        return getOrCreate(key) {
            providerVisualRepository.loadDocumentIcon(document, size)
        }
    }

    suspend fun getRootIcon(
        root: DocumentRootInfo,
        size: Int = 128
    ): Bitmap? {
        val key = "root-icon:${root.packageName}:${root.authority}:${root.rootId}:${root.iconResId}:$size"
        return getOrCreate(key) {
            providerVisualRepository.loadRootIcon(root, size)
        }
    }

    private suspend fun getOrCreate(
        key: String,
        loader: suspend () -> Bitmap?
    ): Bitmap? {
        memoryCache[key]?.let { return it }

        if (hasFreshNegativeEntry(key)) return null

        val lock = keyLocks[(key.hashCode() and Int.MAX_VALUE) % keyLocks.size]

        return lock.withLock {
            memoryCache[key]?.let { return@withLock it }
            if (hasFreshNegativeEntry(key)) return@withLock null

            currentCoroutineContext().ensureActive()

            val result = globalLoadSemaphore.withPermit {
                loader()
            }

            if (result != null) {
                memoryCache.put(key, result)
            } else {
                putNegativeEntry(key)
            }

            result
        }
    }

    private fun hasFreshNegativeEntry(key: String): Boolean {
        val expiresAt = negativeCache[key] ?: return false
        return if (expiresAt > SystemClock.elapsedRealtime()) {
            true
        } else {
            negativeCache.remove(key, expiresAt)
            false
        }
    }

    private fun putNegativeEntry(key: String) {
        negativeCache[key] = SystemClock.elapsedRealtime() + 30_000L
    }

    companion object {
        @Volatile
        private var instance: ImageRepository? = null

        fun get(context: Context): ImageRepository {
            return instance ?: synchronized(this) {
                instance ?: ImageRepository(context).also { instance = it }
            }
        }
    }
}
