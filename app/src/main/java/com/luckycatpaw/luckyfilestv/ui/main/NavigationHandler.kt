package com.luckycatpaw.luckyfilestv.ui.main

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.data.repository.StorageRepository
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiEvent
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiState
import com.luckycatpaw.luckyfilestv.util.hasAllFilesAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedHashMap

internal class NavigationHandler(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val uiState: MutableStateFlow<MainUiState>,
    private val fileRepository: FileRepository,
    private val storageRepository: StorageRepository,
    private val eventChannel: Channel<MainUiEvent>,
    private val onPendingPathChanged: (String?) -> Unit,
    private val getSettings: () -> FileManagerSettings
) {
    private var directoryLoadJob: Job? = null
    private var directoryLoadGeneration = 0
    private val directorySnapshots = object :
        LinkedHashMap<String, DirectorySnapshot>(
            DIRECTORY_SNAPSHOT_LIMIT,
            0.75f,
            true
        ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DirectorySnapshot>
        ): Boolean = size > DIRECTORY_SNAPSHOT_LIMIT
    }

    fun clearSnapshots() {
        directorySnapshots.clear()
    }

    fun showStorages(focusPath: String? = null) {
        directoryLoadGeneration++
        directoryLoadJob?.cancel()

        modelScope.launch {
            val storages = storageRepository.getStorages()

            uiState.update { it.copy(
                currentPath = null,
                currentStorageRoot = null,
                title = appContext.getString(R.string.storage),
                focusTargetPath = focusPath?.takeIf { target ->
                    storages.any { it.path == target }
                },
                browserItems = storages
            )}
        }
    }

    fun openDirectory(
        path: String,
        focusPath: String? = null,
        restoreCachedState: Boolean = false
    ) {
        if (!hasAllFilesAccess()) {
            onPendingPathChanged(path)
            eventChannel.trySend(MainUiEvent.RequestStorageAccess)
            return
        }

        onPendingPathChanged(null)
        val enteringNewDirectory = uiState.value.currentPath != path

        uiState.update { it.copy(currentPath = path) }

        val settings = getSettings()
        val loadGeneration = ++directoryLoadGeneration
        val cachedSnapshot = directorySnapshots[path]?.let { snapshot ->
            if (enteringNewDirectory && !restoreCachedState) {
                snapshot.copy(
                    gridPosition = TvGridPosition()
                ).also { resetSnapshot ->
                    directorySnapshots[path] = resetSnapshot
                }
            } else {
                snapshot
            }
        }

        val cachedItems = cachedSnapshot?.items
        val effectiveFocusPath = if (restoreCachedState && cachedSnapshot == null) {
            null
        } else {
            focusPath
        }

        uiState.update { it.copy(
            focusTargetPath = effectiveFocusPath,
            browserItems = cachedItems ?: emptyList()
        )}

        directoryLoadJob?.cancel()
        directoryLoadJob = modelScope.launch {
            val itemsResult = fileRepository.getItems(
                path = path,
                hideFolderJpg = settings.hideFolderJpg,
                sortMode = settings.sortMode,
                sortAscending = settings.sortAscending,
                foldersFirst = settings.foldersFirst
            )

            if (
                uiState.value.currentPath != path ||
                directoryLoadGeneration != loadGeneration
            ) {
                return@launch
            }

            val items = itemsResult.getOrElse { error ->
                eventChannel.trySend(
                    MainUiEvent.ShowMessage(
                        error.message ?: appContext.getString(R.string.folder_load_failed)
                    )
                )
                return@launch
            }

            directorySnapshots[path] = DirectorySnapshot(
                items = items.takeIf {
                    it.size <= MAX_CACHED_ITEMS_PER_DIRECTORY
                },
                gridPosition = directorySnapshots[path]?.gridPosition ?: TvGridPosition()
            )

            uiState.update { it.copy(
                focusTargetPath = effectiveFocusPath?.takeIf { target ->
                    items.any { it.path == target }
                },
                browserItems = items,
                title = calculateTitle(path)
            )}
        }
    }

    private suspend fun calculateTitle(path: String?): String {
        if (path == null) return appContext.getString(R.string.storage)

        val storages = storageRepository.getStorages()
        storages.firstOrNull { it.path == path }?.let { return it.name }

        return File(path).name.takeIf { it.isNotBlank() } ?: path
    }

    fun directoryGridPosition(path: String?): TvGridPosition? {
        return path?.let { directorySnapshots[it]?.gridPosition }
    }

    fun saveDirectoryGridPosition(path: String?, position: TvGridPosition) {
        val directoryPath = path ?: return
        val snapshot = directorySnapshots[directoryPath] ?: return
        directorySnapshots[directoryPath] = snapshot.copy(gridPosition = position)
    }

    fun navigateBack() {
        val path = uiState.value.currentPath ?: return

        modelScope.launch {
            if (isStorageRoot(path)) {
                showStorages(focusPath = path)
                return@launch
            }

            val parent = File(path).parentFile

            if (parent == null) {
                showStorages()
                return@launch
            }

            openDirectory(
                path = parent.absolutePath,
                focusPath = path,
                restoreCachedState = true
            )
        }
    }

    private suspend fun isStorageRoot(path: String): Boolean {
        return storageRepository.getStorages().any { it.path == path }
    }

    private data class DirectorySnapshot(
        val items: List<BrowserItem>?,
        val gridPosition: TvGridPosition
    )

    companion object {
        private const val DIRECTORY_SNAPSHOT_LIMIT = 20
        private const val MAX_CACHED_ITEMS_PER_DIRECTORY = 500
    }
}
