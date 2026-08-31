package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageRepository(private val context: Context) {

    private val storageManager: StorageManager =
        context.getSystemService(StorageManager::class.java)

    private var storageCallback: StorageManager.StorageVolumeCallback? = null

    /**
     * Last enumeration of the mounted volumes.
     *
     * Every directory change asked the platform again — a binder round trip plus a
     * `getDescription` per volume — and navigating back did it twice, once to recognise a
     * storage root and once to build the title. Mounts change rarely, so the result is kept
     * and dropped again by the volume callback below. The timestamp is only a backstop for
     * the periods in which nobody is watching, e.g. while the app is in the background.
     */
    @Volatile
    private var cachedStorages: CachedStorages? = null

    suspend fun getStorages(): List<BrowserItem.Storage> = withContext(Dispatchers.IO) {
        getStoragesSync()
    }

    fun getStoragesSync(): List<BrowserItem.Storage> {
        cachedStorages
            ?.takeIf { SystemClock.elapsedRealtime() - it.readAtMillis < CACHE_TTL_MILLIS }
            ?.let { return it.storages }

        val storages = readStorages()
        cachedStorages = CachedStorages(storages, SystemClock.elapsedRealtime())
        return storages
    }

    /** Forces the next lookup to ask the platform again. */
    fun invalidate() {
        cachedStorages = null
    }

    private fun readStorages(): List<BrowserItem.Storage> {
        return storageManager.storageVolumes
            .filter { volume ->
                volume.state == Environment.MEDIA_MOUNTED ||
                    volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
            }
            .mapNotNull { volume ->
                val directory = volume.directory
                    ?: return@mapNotNull null

                BrowserItem.Storage(
                    name = if (volume.isPrimary) {
                        context.getString(R.string.internal_storage)
                    } else {
                        volume.getDescription(context)
                    },
                    path = directory.absolutePath,
                    removable = volume.isRemovable
                )
            }
            .sortedBy { storage ->
                storage.removable
            }
    }

    fun startWatching(onChanged: () -> Unit) {
        if (storageCallback != null) {
            return
        }

        val callback =
            object : StorageManager.StorageVolumeCallback() {

                override fun onStateChanged(volume: StorageVolume) {
                    invalidate()
                    onChanged()
                }
            }

        storageCallback = callback

        storageManager.registerStorageVolumeCallback(
            context.mainExecutor,
            callback
        )
    }

    fun stopWatching() {
        val callback = storageCallback
            ?: return

        storageManager.unregisterStorageVolumeCallback(
            callback
        )

        storageCallback = null
    }

    private data class CachedStorages(val storages: List<BrowserItem.Storage>, val readAtMillis: Long)

    private companion object {
        const val CACHE_TTL_MILLIS = 10_000L
    }
}
