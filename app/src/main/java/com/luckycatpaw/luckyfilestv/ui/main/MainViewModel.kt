package com.luckycatpaw.luckyfilestv.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.data.repository.SettingsRepository
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.toListOptions
import com.luckycatpaw.luckyfilestv.data.source.SourceMessages
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.Volume
import com.luckycatpaw.luckyfilestv.data.source.VolumeKind
import com.luckycatpaw.luckyfilestv.data.source.local.LocalVolumeRepository
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbFileSource
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbSessionPool
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbShare
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbShareRepository
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbShareStore
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiEvent
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiState
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferConflictAnswer
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferMode
import com.luckycatpaw.luckyfilestv.util.hasAllFilesAccess
import com.luckycatpaw.luckyfilestv.util.hasLocalNetworkAccess
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
    private val eventChannel = Channel<MainUiEvent>(Channel.BUFFERED)
    private val fileTreeWalker = FileTreeWalker()

    internal val events = eventChannel.receiveAsFlow()
    private val volumeRepository = LocalVolumeRepository(appContext)
    private val smbShareRepository = SmbShareRepository(appContext)
    private val settingsRepository = SettingsRepository(appContext)
    private val fileRepository = FileRepository(
        context = appContext,
        sources = FileSourceRegistry.create(appContext, volumeRepository, fileTreeWalker, smbShareRepository)
    )

    private val _uiState = MutableStateFlow(MainUiState())
    internal val uiState = combine(
        _uiState,
        settingsRepository.settings
    ) { state, settings ->
        state.copy(settings = settings)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    private var pendingPath: String? = null
    private var pendingNetworkVolume: BrowserItem.Storage? = null
    private var currentSettings = FileManagerSettings()

    /**
     * The item the grid last reported as focused.
     *
     * Deliberately not part of [MainUiState]: it is never rendered, only read once at the
     * moment an action needs a return target. As state it emitted on every D-pad press and
     * recomposed the whole screen tree — header buttons, focus index lookups and all — for a
     * value nothing on screen depends on.
     */
    internal var focusedPath: String? = null
        private set

    private val navigationHandler = NavigationHandler(
        appContext = appContext,
        modelScope = viewModelScope,
        uiState = _uiState,
        fileRepository = fileRepository,
        eventChannel = eventChannel,
        onPendingPathChanged = { pendingPath = it },
        getSettings = { currentSettings }
    )

    private val transferManager = TransferManager(
        appContext = appContext,
        modelScope = viewModelScope,
        uiState = _uiState,
        onTransferFinished = { targetPath, resultFocusPath ->
            navigationHandler.openDirectory(targetPath, resultFocusPath)
        },
        onNotificationPermissionNeeded = {
            eventChannel.trySend(MainUiEvent.RequestNotificationAccess)
        }
    )

    init {
        viewModelScope.launch {
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
            openDirectory(path, focusedPath)
        }
    }

    internal suspend fun getProperties(path: String): Result<FileProperties> = fileRepository.getProperties(path)

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
        viewModelScope.launch {
            val storages = fileRepository.roots()

            // Read only after the suspending call above. Assembling the volume list takes long
            // enough for the user to navigate in the meantime, so a snapshot taken beforehand
            // can describe a screen that no longer exists.
            val state = _uiState.value
            val activeStorage = state.currentStorageRoot

            if (
                state.currentPath != null &&
                activeStorage != null &&
                storages.none { it.path == activeStorage }
            ) {
                showStorages()
                return@launch
            }

            // The root check is repeated inside update so that navigation racing with this
            // coroutine cannot be overwritten by a storage list that is already outdated.
            _uiState.update { currentState ->
                if (currentState.currentPath != null) {
                    currentState
                } else {
                    currentState.copy(
                        browserItems = storages,
                        focusTargetPath = focusedPath?.takeIf { path ->
                            storages.any { it.path == path }
                        }
                    )
                }
            }
        }
    }

    internal fun startWatchingStorage() {
        volumeRepository.startWatching(::handleStorageChange)
    }

    internal fun stopWatchingStorage() {
        volumeRepository.stopWatching()
    }

    internal fun resumeAfterStoragePermission() {
        val path = pendingPath ?: return
        if (hasAllFilesAccess()) {
            openDirectory(path)
        }
    }

    internal fun setFocusedPath(path: String?) {
        focusedPath = path
    }

    internal fun setFocusTargetPath(path: String?) {
        _uiState.update { it.copy(focusTargetPath = path) }
    }

    internal fun setCurrentStorageRoot(path: String?) {
        _uiState.update { it.copy(currentStorageRoot = path) }
    }

    // Browser actions
    //
    // These used to live in BrowserActionHandler, which held a reference to this
    // ViewModel while the ViewModel held the handler. Failures are reported through
    // the existing event channel instead of a Toast, so no Context is needed here.

    internal fun openItem(item: BrowserItem) {
        when (item) {
            is BrowserItem.Storage -> {
                // Asking once the user actually opens a share keeps the prompt in context,
                // and a device with no share configured never sees it at all.
                if (item.volume.kind == VolumeKind.NETWORK && !appContext.hasLocalNetworkAccess()) {
                    pendingNetworkVolume = item
                    eventChannel.trySend(MainUiEvent.RequestLocalNetworkAccess)
                    return
                }

                setCurrentStorageRoot(item.path)
                openDirectory(item.path)
            }

            is BrowserItem.Folder -> openDirectory(item.path)

            is BrowserItem.File -> Unit
        }
    }

    /** Opens the share the permission was asked for, once the user has decided. */
    internal fun resumeAfterLocalNetworkPermission() {
        val pending = pendingNetworkVolume ?: return
        pendingNetworkVolume = null

        if (appContext.hasLocalNetworkAccess()) openItem(pending)
    }

    internal fun startTransfer(mode: TransferMode, items: List<BrowserItem>, onStarted: () -> Unit) {
        if (items.isEmpty()) return
        prepareTransfer(mode, items)
        onStarted()
    }

    internal fun renameItem(item: BrowserItem, newName: String, onFinished: () -> Unit) {
        viewModelScope.launch {
            fileRepository.rename(item.path, newName)
                .onSuccess { newPath ->
                    onFinished()
                    refreshCurrentDirectory(focusPath = newPath)
                }
                .onFailure { reportFailure(it, R.string.rename_failed) }
        }
    }

    /**
     * Deletes [items] one by one. [onProgress] receives the 1-based position of the item
     * about to be deleted, letting the caller drive a progress overlay without running
     * its own coroutine.
     */
    internal fun deleteItems(
        items: List<BrowserItem>,
        onProgress: (index: Int, total: Int, item: BrowserItem) -> Unit = { _, _, _ -> },
        onFinished: (successCount: Int, failureCount: Int) -> Unit
    ) {
        if (items.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var failureCount = 0

            items.forEachIndexed { index, item ->
                onProgress(index + 1, items.size, item)
                fileRepository.delete(item.path)
                    .onSuccess { successCount++ }
                    .onFailure { failureCount++ }
            }

            if (successCount > 0) refreshCurrentDirectory()
            onFinished(successCount, failureCount)
        }
    }

    internal fun createFolderIn(parentPath: String, name: String, onFinished: (String) -> Unit) {
        viewModelScope.launch {
            fileRepository.createFolder(parentPath, name)
                .onSuccess { newPath ->
                    onFinished(newPath)
                    refreshCurrentDirectory(focusPath = newPath)
                }
                .onFailure { reportFailure(it, R.string.folder_create_failed) }
        }
    }

    /**
     * Stores a share and shows the overview again, where the tile then appears or changes.
     */
    internal fun saveSmbShare(share: SmbShare) {
        viewModelScope.launch {
            runCatching { smbShareRepository.save(share) }
                .onSuccess { showStorages() }
                .onFailure { reportFailure(it, R.string.error_generic) }
        }
    }

    /** Looks up the configuration behind a volume tile so the editor can be filled with it. */
    internal fun loadShare(configId: String, onLoaded: (SmbShare?) -> Unit) {
        viewModelScope.launch {
            onLoaded(smbShareRepository.shares().firstOrNull { it.id == configId })
        }
    }

    internal fun removeSmbShare(volume: Volume) {
        val configId = volume.configId ?: return

        viewModelScope.launch {
            runCatching { smbShareRepository.remove(configId) }
                .onSuccess { showStorages() }
                .onFailure { reportFailure(it, R.string.error_generic) }
        }
    }

    /**
     * Tries the entered share once, without storing it.
     *
     * Runs against a session of its own: the values are not saved yet, and a pooled session
     * of the previous credentials would answer for them. Typing a password on a remote
     * control is slow enough that finding out about a typo only after saving is a poor deal.
     */
    internal fun testSmbShare(share: SmbShare, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val sessions = SmbSessionPool()
            val source = SmbFileSource(SmbShareStore { listOf(share) }, sessions)

            val result = runCatching {
                source.list(share.path, currentSettings.toListOptions())
            }

            sessions.closeAll()

            result
                .onSuccess { onResult(true, appContext.getString(R.string.share_test_success)) }
                .onFailure { error ->
                    onResult(false, SourceMessages(appContext).localize(error, SourceOperation.LIST))
                }
        }
    }

    private fun reportFailure(error: Throwable, fallbackResId: Int) {
        eventChannel.trySend(
            MainUiEvent.ShowMessage(error.message ?: appContext.getString(fallbackResId))
        )
    }

    override fun onCleared() {
        navigationHandler.clearSnapshots()
        volumeRepository.stopWatching()
        eventChannel.close()
    }
}
