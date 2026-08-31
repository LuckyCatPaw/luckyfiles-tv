package com.luckycatpaw.luckyfilestv.ui.common

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.data.repository.StorageRepository
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.util.FileUtil
import java.io.File
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a caller needs to know about the directory itself, beyond its contents. */
internal data class DirectoryInfo(val writable: Boolean)

internal class LocalBrowserCoordinator<T>(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val fileRepository: FileRepository,
    private val storageRepository: StorageRepository,
    private val cacheLimit: Int = 12,
    /**
     * Above this many entries a directory is not kept for the way back.
     *
     * The bound exists to cap memory, but it used to sit at a point where it excluded exactly
     * the directories whose re-listing hurts most. An entry is a small object with two string
     * references, so a few thousand of them are a few hundred kilobytes — cheaper than
     * listing, stat-ing and sorting them again on every step back up the tree.
     */
    private val maxItemsToCache: Int = 5000
) {
    private var loadJob: Job? = null
    private var loadGeneration = 0

    private val snapshots = object : LinkedHashMap<String, DirectorySnapshot<T>>(cacheLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DirectorySnapshot<T>>): Boolean =
            size > cacheLimit
    }

    fun getGridPosition(path: String?): TvGridPosition? = path?.let { snapshots[it]?.gridPosition }

    fun saveGridPosition(path: String?, position: TvGridPosition) {
        val p = path ?: return
        snapshots[p]?.let { snapshots[p] = it.copy(gridPosition = position) }
    }

    fun clearCache() {
        snapshots.clear()
    }

    fun cancelLoading() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
    }

    fun loadDirectory(
        path: String,
        settings: FileManagerSettings,
        restoreCachedState: Boolean,
        isCurrentPath: (String) -> Boolean,
        filter: (BrowserItem) -> T?,
        onLoading: (cachedItems: List<T>?) -> Unit,
        onLoaded: (items: List<T>, title: String, info: DirectoryInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        val generation = ++loadGeneration
        loadJob?.cancel()

        val enteringNew = !isCurrentPath(path)
        val cached = snapshots[path]?.let {
            if (enteringNew && !restoreCachedState) {
                it.copy(gridPosition = TvGridPosition()).also { s -> snapshots[path] = s }
            } else {
                it
            }
        }

        onLoading(cached?.items)

        loadJob = modelScope.launch {
            val result = FileUtil.runCancellable {
                val rawItems = fileRepository.getItems(
                    path,
                    settings.hideFolderJpg,
                    settings.sortMode,
                    settings.sortAscending,
                    settings.foldersFirst
                ).getOrThrow()

                val transformedItems = rawItems.mapNotNull(filter)
                val title = calculateTitle(path)

                Triple(transformedItems, title, DirectoryInfo(writable = File(path).canWrite()))
            }

            if (!isCurrentPath(path) || loadGeneration != generation) return@launch

            val (items, title, info) = result.getOrElse { error ->
                onError(error.message ?: appContext.getString(R.string.folder_load_failed))
                return@launch
            }

            snapshots[path] = DirectorySnapshot(
                items = if (items.size <= maxItemsToCache) items else null,
                gridPosition = snapshots[path]?.gridPosition ?: TvGridPosition()
            )

            onLoaded(items, title, info)
        }
    }

    private suspend fun calculateTitle(path: String): String {
        val file = File(path)
        // One realpath(2) instead of one per volume: the old version resolved the browsed
        // path again inside the loop body.
        val canonical = runCatching { file.canonicalPath }.getOrNull()

        storageRepository.getStorages().firstOrNull { storage ->
            if (storage.path == path) {
                true
            } else if (canonical == null) {
                false
            } else {
                runCatching { File(storage.path).canonicalPath == canonical }.getOrDefault(false)
            }
        }?.let { return it.name }

        return file.name.takeIf { it.isNotBlank() } ?: path
    }

    private data class DirectorySnapshot<T>(val items: List<T>?, val gridPosition: TvGridPosition)
}
