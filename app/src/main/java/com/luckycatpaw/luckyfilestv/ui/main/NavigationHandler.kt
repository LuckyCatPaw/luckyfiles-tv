package com.luckycatpaw.luckyfilestv.ui.main

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.ui.common.BrowserCoordinator
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiEvent
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiState
import com.luckycatpaw.luckyfilestv.util.hasAllFilesAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NavigationHandler(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val uiState: MutableStateFlow<MainUiState>,
    private val fileRepository: FileRepository,
    private val eventChannel: Channel<MainUiEvent>,
    private val onPendingPathChanged: (String?) -> Unit,
    private val getSettings: () -> FileManagerSettings
) {
    private var navigationGeneration = 0

    private val coordinator = BrowserCoordinator<BrowserItem>(
        appContext = appContext,
        modelScope = modelScope,
        fileRepository = fileRepository,
        cacheLimit = 20,
        maxItemsToCache = 5000
    )

    fun clearSnapshots() {
        coordinator.clearCache()
    }

    fun showStorages(focusPath: String? = null) {
        val generation = ++navigationGeneration
        coordinator.cancelLoading()
        coordinator.clearCache()

        modelScope.launch {
            val storages = fileRepository.roots()
            if (generation != navigationGeneration) return@launch

            uiState.update { state ->
                state.copy(
                    currentPath = null,
                    currentStorageRoot = null,
                    title = appContext.getString(R.string.storage),
                    focusTargetPath = focusPath?.takeIf { target ->
                        storages.any { it.path == target }
                    },
                    browserItems = storages
                )
            }
        }
    }

    fun openDirectory(path: String, focusPath: String? = null, restoreCachedState: Boolean = false) {
        if (!hasAllFilesAccess()) {
            onPendingPathChanged(path)
            eventChannel.trySend(MainUiEvent.RequestStorageAccess)
            return
        }

        onPendingPathChanged(null)
        navigationGeneration++
        val settings = getSettings()

        coordinator.loadDirectory(
            path = path,
            settings = settings,
            restoreCachedState = restoreCachedState,
            isCurrentPath = { uiState.value.currentPath == it },
            filter = { it },
            onLoading = { cachedItems ->
                uiState.update {
                    it.copy(
                        currentPath = path,
                        focusTargetPath = if (restoreCachedState && cachedItems == null) null else focusPath,
                        browserItems = cachedItems ?: emptyList()
                    )
                }
            },
            onLoaded = { items, title, _ ->
                uiState.update { state ->
                    state.copy(
                        focusTargetPath = if (restoreCachedState) {
                            focusPath
                        } else {
                            focusPath?.takeIf { target ->
                                items.any {
                                    it.path ==
                                        target
                                }
                            }
                        },
                        browserItems = items,
                        title = title
                    )
                }
            },
            onError = { message ->
                eventChannel.trySend(MainUiEvent.ShowMessage(message))
            }
        )
    }

    fun directoryGridPosition(path: String?): TvGridPosition? = coordinator.getGridPosition(path)

    fun saveDirectoryGridPosition(path: String?, position: TvGridPosition) {
        coordinator.saveGridPosition(path, position)
    }

    fun navigateBack() {
        val path = uiState.value.currentPath ?: return

        modelScope.launch {
            if (isStorageRoot(path)) {
                showStorages(focusPath = path)
                return@launch
            }

            val parent = fileRepository.parentOf(path)
            if (parent == null) {
                showStorages()
                return@launch
            }

            openDirectory(
                path = parent,
                focusPath = path,
                restoreCachedState = true
            )
        }
    }

    private suspend fun isStorageRoot(path: String): Boolean = fileRepository.isRoot(path)
}
