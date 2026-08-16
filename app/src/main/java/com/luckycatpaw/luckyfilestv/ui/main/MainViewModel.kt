package com.luckycatpaw.luckyfilestv.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.data.repository.SettingsRepository
import com.luckycatpaw.luckyfilestv.data.repository.StorageRepository
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiEvent
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiState
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferConflictAnswer
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferMode
import com.luckycatpaw.luckyfilestv.util.hasAllFilesAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eventChannel = Channel<MainUiEvent>(Channel.BUFFERED)
    private val fileTreeWalker = FileTreeWalker()

    internal val events = eventChannel.receiveAsFlow()
    private val storageRepository = StorageRepository(appContext)
    private val settingsRepository = SettingsRepository(appContext)
    private val fileRepository = FileRepository(appContext, fileTreeWalker)

    private val _uiState = MutableStateFlow(MainUiState())
    internal val uiState = combine(
        _uiState,
        settingsRepository.settings
    ) { state, settings ->
        state.copy(settings = settings)
    }.stateIn(
        scope = modelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    private var pendingPath: String? = null
    private var currentSettings = FileManagerSettings()

    private val navigationHandler = NavigationHandler(
        appContext = appContext,
        modelScope = modelScope,
        uiState = _uiState,
        fileRepository = fileRepository,
        storageRepository = storageRepository,
        eventChannel = eventChannel,
        onPendingPathChanged = { pendingPath = it },
        getSettings = { currentSettings }
    )

    private val transferManager = TransferManager(
        appContext = appContext,
        modelScope = modelScope,
        uiState = _uiState,
        onTransferFinished = { targetPath, resultFocusPath ->
            navigationHandler.openDirectory(targetPath, resultFocusPath)
        },
        onNotificationPermissionNeeded = {
            eventChannel.trySend(MainUiEvent.RequestNotificationAccess)
        }
    )

    internal val browserActions: BrowserActionHandler by lazy {
        BrowserActionHandler(
            appContext = appContext,
            modelScope = modelScope,
            viewModel = this
        )
    }

    init {
        modelScope.launch {
            settingsRepository.settings.collect { settings ->
                applySettings(settings)
            }
        }
        showStorages()
    }

    private fun applySettings(settings: FileManagerSettings) {
        if (settings != currentSettings) {
            navigationHandler.clearSnapshots()
        }

        currentSettings = settings

        _uiState.value.currentPath?.let { path ->
            openDirectory(path, _uiState.value.focusedPath)
        }
    }

    internal suspend fun getProperties(path: String): Result<FileProperties> = fileRepository.getProperties(path)

    internal suspend fun rename(path: String, newName: String): Result<String> = fileRepository.rename(path, newName)

    internal suspend fun delete(path: String): Result<Unit> = fileRepository.delete(path)

    internal suspend fun createFolder(parentPath: String, name: String): Result<String> =
        fileRepository.createFolder(parentPath, name)

    // Transfer Delegation
    internal fun prepareTransfer(mode: TransferMode, sources: List<BrowserItem>) =
        transferManager.prepareTransfer(mode, sources)

    internal fun cancelPreparedTransfer() = transferManager.cancelPreparedTransfer()

    internal fun startPreparedTransfer(targetPath: String) = transferManager.startPreparedTransfer(targetPath)

    internal fun cancelRunningTransfer() = transferManager.cancelRunningTransfer()

    internal fun answerTransferConflict(answer: TransferConflictAnswer) = transferManager.answerTransferConflict(answer)

    internal fun consumeTransferCompletion() = transferManager.consumeTransferCompletion()

    // Navigation Delegation
    internal fun showStorages(focusPath: String? = null) = navigationHandler.showStorages(focusPath)

    internal fun openDirectory(path: String, focusPath: String? = null, restoreCachedState: Boolean = false) =
        navigationHandler.openDirectory(path, focusPath, restoreCachedState)

    internal fun directoryGridPosition(path: String?): TvGridPosition? = navigationHandler.directoryGridPosition(path)

    internal fun saveDirectoryGridPosition(path: String?, position: TvGridPosition) =
        navigationHandler.saveDirectoryGridPosition(path, position)

    internal fun refreshCurrentDirectory(focusPath: String? = null) {
        val path = _uiState.value.currentPath

        if (path == null) {
            showStorages(focusPath)
        } else {
            openDirectory(path = path, focusPath = focusPath)
        }
    }

    internal fun navigateBack() = navigationHandler.navigateBack()

    // Settings
    internal suspend fun setLanguageTag(value: String?) = settingsRepository.setLanguageTag(value)
    internal suspend fun setHideFolderJpg(value: Boolean) = settingsRepository.setHideFolderJpg(value)
    internal suspend fun setUseFolderJpgAsIcon(value: Boolean) = settingsRepository.setUseFolderJpgAsIcon(value)
    internal suspend fun setOptimizeFileNames(value: Boolean) = settingsRepository.setOptimizeFileNames(value)
    internal suspend fun setSortMode(value: FileSortMode) = settingsRepository.setSortMode(value)
    internal suspend fun setSortAscending(value: Boolean) = settingsRepository.setSortAscending(value)
    internal suspend fun setFoldersFirst(value: Boolean) = settingsRepository.setFoldersFirst(value)

    // Storage Management
    internal fun handleStorageChange() {
        val activeStorage = _uiState.value.currentStorageRoot
        val state = _uiState.value

        modelScope.launch {
            val storages = storageRepository.getStorages()

            if (
                state.currentPath != null &&
                activeStorage != null &&
                storages.none { it.path == activeStorage }
            ) {
                showStorages()
                return@launch
            }

            if (state.currentPath == null) {
                val previousFocus = state.focusedPath

                _uiState.update { currentState ->
                    currentState.copy(
                        browserItems = storages,
                        focusTargetPath = previousFocus?.takeIf { path ->
                            storages.any { it.path == path }
                        }
                    )
                }
            }
        }
    }

    internal fun startWatchingStorage() {
        storageRepository.startWatching(::handleStorageChange)
    }

    internal fun stopWatchingStorage() {
        storageRepository.stopWatching()
    }

    internal fun resumeAfterStoragePermission() {
        val path = pendingPath ?: return
        if (hasAllFilesAccess()) {
            openDirectory(path)
        }
    }

    internal fun setFocusedPath(path: String?) {
        _uiState.update { it.copy(focusedPath = path) }
    }

    internal fun setFocusTargetPath(path: String?) {
        _uiState.update { it.copy(focusTargetPath = path) }
    }

    internal fun setCurrentStorageRoot(path: String?) {
        _uiState.update { it.copy(currentStorageRoot = path) }
    }

    override fun onCleared() {
        navigationHandler.clearSnapshots()
        modelScope.cancel()
        storageRepository.stopWatching()
        eventChannel.close()
        super.onCleared()
    }
}
