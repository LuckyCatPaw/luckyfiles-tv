package com.luckycatpaw.luckyfilestv.data.source.local

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.Volume
import com.luckycatpaw.luckyfilestv.data.source.VolumeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The volumes the platform has mounted.
 *
 * All of them come from the system, so none of them carries a [Volume.actions] entry: the
 * user can neither reconfigure internal storage nor remove a USB stick from within the app.
 */
internal class LocalVolumeRepository(private val context: Context) {

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
    private var cachedVolumes: CachedVolumes? = null

    suspend fun volumes(): List<Volume> = withContext(Dispatchers.IO) {
        volumesSync()
    }

    fun volumesSync(): List<Volume> {
        cachedVolumes
            ?.takeIf { SystemClock.elapsedRealtime() - it.readAtMillis < CACHE_TTL_MILLIS }
            ?.let { return it.volumes }

        val volumes = readVolumes()
        cachedVolumes = CachedVolumes(volumes, SystemClock.elapsedRealtime())
        return volumes
    }

    /** Forces the next lookup to ask the platform again. */
    fun invalidate() {
        cachedVolumes = null
    }

    private fun readVolumes(): List<Volume> {
        return storageManager.storageVolumes
            .filter { volume ->
                volume.state == Environment.MEDIA_MOUNTED ||
                    volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
            }
            .mapNotNull { volume ->
                val directory = volume.directory
                    ?: return@mapNotNull null

                Volume(
                    path = SourcePath.of(directory),
                    name = if (volume.isPrimary) {
                        context.getString(R.string.internal_storage)
                    } else {
                        volume.getDescription(context)
                    },
                    kind = if (volume.isRemovable) VolumeKind.REMOVABLE else VolumeKind.INTERNAL
                )
            }
            .sortedBy { volume ->
                volume.kind == VolumeKind.REMOVABLE
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

    private data class CachedVolumes(val volumes: List<Volume>, val readAtMillis: Long)

    private companion object {
        const val CACHE_TTL_MILLIS = 10_000L
    }
}
