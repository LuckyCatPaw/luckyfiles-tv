package com.luckycatpaw.luckyfilestv.ui.common

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.data.repository.StorageRepository
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.util.FileUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedHashMap

internal class LocalBrowserCoordinator<T>(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val fileRepository: FileRepository,
    private val storageRepository: StorageRepository,
    private val cacheLimit: Int = 12,
    private val maxItemsToCache: Int = 1000
) {
    private var loadJob: Job? = null
    private var loadGeneration = 0
    
    private val snapshots = object : LinkedHashMap<String, DirectorySnapshot<T>>(cacheLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DirectorySnapshot<T>>): Boolean = size > cacheLimit
    }

    fun getGridPosition(path: String?): TvGridPosition? = path?.let { snapshots[it]?.gridPosition }

    fun saveGridPosition(path: String?, position: TvGridPosition) {
        val p = path ?: return
        snapshots[p]?.let { snapshots[p] = it.copy(gridPosition = position) }
    }

    fun clearCache() {
        snapshots.clear()
    }

    fun loadDirectory(
        path: String,
        settings: FileManagerSettings,
        restoreCachedState: Boolean,
        isCurrentPath: (String) -> Boolean,
        filter: (BrowserItem) -> T?,
        onLoading: (cachedItems: List<T>?) -> Unit,
        onLoaded: (items: List<T>, title: String, metadata: Map<String, Any>) -> Unit,
        onError: (String) -> Unit
    ) {
        val generation = ++loadGeneration
        loadJob?.cancel()

        val enteringNew = !isCurrentPath(path)
        val cached = snapshots[path]?.let { 
            if (enteringNew && !restoreCachedState) {
                it.copy(gridPosition = TvGridPosition()).also { s -> snapshots[path] = s }
            } else it
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
                
                val metadata = mutableMapOf<String, Any>()
                // common metadata
                metadata["writable"] = File(path).canWrite()
                metadata["safRestricted"] = FileUtil.isSafRestrictedPath(path)

                Triple(transformedItems, title, metadata)
            }

            if (!isCurrentPath(path) || loadGeneration != generation) return@launch

            val (items, title, metadata) = result.getOrElse { error ->
                onError(error.message ?: appContext.getString(R.string.folder_load_failed))
                return@launch
            }

            snapshots[path] = DirectorySnapshot(
                items = if (items.size <= maxItemsToCache) items else null,
                gridPosition = snapshots[path]?.gridPosition ?: TvGridPosition(),
                metadata = metadata
            )

            onLoaded(items, title, metadata)
        }
    }

    private suspend fun calculateTitle(path: String): String {
        val storages = storageRepository.getStorages()
        storages.firstOrNull { 
            runCatching { File(it.path).canonicalPath == File(path).canonicalPath }.getOrDefault(it.path == path)
        }?.let { return it.name }

        return File(path).name.takeIf { it.isNotBlank() } ?: path
    }

    private data class DirectorySnapshot<T>(
        val items: List<T>?,
        val gridPosition: TvGridPosition,
        val metadata: Map<String, Any>
    )
}
