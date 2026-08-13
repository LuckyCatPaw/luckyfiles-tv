package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageRepository(
    private val context: Context
) {

    private val storageManager: StorageManager =
        context.getSystemService(StorageManager::class.java)

    private var storageCallback: StorageManager.StorageVolumeCallback? = null

    suspend fun getStorages(): List<BrowserItem.Storage> = withContext(Dispatchers.IO) {
        storageManager.storageVolumes
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

    fun startWatching(
        onChanged: () -> Unit
    ) {
        if (storageCallback != null) {
            return
        }

        val callback =
            object : StorageManager.StorageVolumeCallback() {

                override fun onStateChanged(
                    volume: StorageVolume
                ) {
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
}
