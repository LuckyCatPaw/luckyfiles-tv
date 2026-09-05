package com.luckycatpaw.luckyfilestv.data.repository

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import com.luckycatpaw.luckyfilestv.data.common.GeneratedThumbnailCache
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Single entry point for every bitmap the UI displays, and the only place in the app that
 * keeps bitmaps in memory. [GeneratedThumbnailCache] below it is disk only and
 * [com.luckycatpaw.luckyfilestv.data.common.LocalThumbnailDecoder] is stateless, so a
 * thumbnail exists exactly once in RAM regardless of how it was produced.
 */
class ImageRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val providerVisualRepository = ProviderVisualRepository.get(appContext)
    private val generatedThumbnailCache = GeneratedThumbnailCache.get(appContext)

    /**
     * An eighth of the heap, bounded so that neither a 1 GB TV stick nor a large-heap device
     * ends up with an unreasonable budget. A 384x240 RGB_565 preview costs about 180 KB, so
     * even the lower bound holds roughly 45 of them, well beyond one screen of the grid.
     */
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()
        .coerceIn(MIN_MEMORY_CACHE_KB, MAX_MEMORY_CACHE_KB)

    private val memoryCache = object : LruCache<String, Bitmap>(maxMemoryKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val keyLocks = Array(32) { Mutex() }

    /**
     * Keys that recently failed to produce a preview, so scrolling past a broken file does
     * not retry it on every pass.
     *
     * Bounded. As an unbounded map it grew by one entry for every file the user scrolled
     * past and only shrank when the same key came up again, which on a one-way walk through
     * a large folder never happens. Evicting the least recently used entry costs nothing:
     * after [NEGATIVE_TTL_MILLIS] it would be thrown away on the next read anyway.
     */
    private val negativeCache = LruCache<String, Long>(NEGATIVE_CACHE_ENTRIES)
    private val globalLoadSemaphore = Semaphore(4)

    /**
     * Hands memory back when the system asks for it. Everything dropped here is still on disk
     * and costs one JPEG decode to come back, so the cache can afford to be generous.
     *
     * Evicted bitmaps are deliberately not recycled: a Compose `Image` may still be drawing
     * one. Releasing the reference is enough, the collector reclaims it once nothing holds it.
     */
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                memoryCache.evictAll()
                negativeCache.evictAll()
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                memoryCache.trimToSize(maxMemoryKb / 2)
        }
    }

    suspend fun getLocalThumbnail(key: String, generator: suspend () -> Bitmap?): Bitmap? = getOrCreate(key) {
        generatedThumbnailCache.getOrCreate(key, generator)
    }

    suspend fun getProviderThumbnail(document: ProviderDocumentInfo, width: Int = 384, height: Int = 240): Bitmap? {
        val key = "provider-thumb:${document.authority}:${document.documentId}:" +
            "${document.lastModified ?: 0L}:${width}x$height"
        return getOrCreate(key) {
            providerVisualRepository.loadThumbnail(document, width, height)
        }
    }

    suspend fun getProviderIcon(document: ProviderDocumentInfo, size: Int = 128): Bitmap? {
        val key = "provider-icon:${document.authority}:${document.iconResId}:$size"
        return getOrCreate(key) {
            providerVisualRepository.loadDocumentIcon(document, size)
        }
    }

    suspend fun getRootIcon(root: DocumentRootInfo, size: Int = 128): Bitmap? {
        val key = "root-icon:${root.packageName}:${root.authority}:" +
            "${root.rootId}:${root.iconResId}:$size"
        return getOrCreate(key) {
            providerVisualRepository.loadRootIcon(root, size)
        }
    }

    private suspend fun getOrCreate(key: String, loader: suspend () -> Bitmap?): Bitmap? {
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
        val expiresAt = negativeCache.get(key) ?: return false
        return if (expiresAt > SystemClock.elapsedRealtime()) {
            true
        } else {
            negativeCache.remove(key)
            false
        }
    }

    private fun putNegativeEntry(key: String) {
        negativeCache.put(key, SystemClock.elapsedRealtime() + NEGATIVE_TTL_MILLIS)
    }

    companion object {
        private const val MIN_MEMORY_CACHE_KB = 8 * 1024
        private const val MAX_MEMORY_CACHE_KB = 48 * 1024

        /** Roughly a dozen screens of a grid, at a key and a timestamp per entry. */
        private const val NEGATIVE_CACHE_ENTRIES = 256
        private const val NEGATIVE_TTL_MILLIS = 30_000L

        @Volatile
        private var instance: ImageRepository? = null

        fun get(context: Context): ImageRepository = instance ?: synchronized(this) {
            instance ?: ImageRepository(context).also { instance = it }
        }
    }
}
