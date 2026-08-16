package com.luckycatpaw.luckyfilestv.ui.picker

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo
import com.luckycatpaw.luckyfilestv.data.provider.DocumentUriMapper
import com.luckycatpaw.luckyfilestv.data.repository.DocumentsProviderRepository
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.data.repository.LocalFileSearchRepository
import com.luckycatpaw.luckyfilestv.data.repository.SettingsRepository
import com.luckycatpaw.luckyfilestv.data.repository.StorageRepository
import com.luckycatpaw.luckyfilestv.ui.common.LocalBrowserCoordinator
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.picker.model.BrowseSnapshot
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerRequest
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiEvent
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiState
import com.luckycatpaw.luckyfilestv.ui.picker.model.ProviderLocation
import com.luckycatpaw.luckyfilestv.util.FileUtil
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import com.luckycatpaw.luckyfilestv.util.hasAllFilesAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal class DocumentPickerViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eventChannel = Channel<PickerUiEvent>(Channel.BUFFERED)

    internal val events: Flow<PickerUiEvent> = eventChannel.receiveAsFlow()
    private val storageRepository = StorageRepository(appContext)
    private val settingsRepository = SettingsRepository(appContext)
    private val documentsRepository = DocumentsProviderRepository(appContext)
    private val fileRepository = FileRepository(appContext)
    private val localSearchRepository = LocalFileSearchRepository(storageRepository)
    private val providerQueryRunner = ProviderQueryRunner(appContext)

    private val _uiState = MutableStateFlow(PickerUiState())
    internal val uiState: StateFlow<PickerUiState> = combine(
        _uiState,
        settingsRepository.settings
    ) { state, settings ->
        state.copy(settings = settings)
    }.stateIn(
        scope = modelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PickerUiState()
    )

    internal var request = PickerRequest(
        mode = PickerMode.OPEN_DOCUMENT,
        allowMultiple = false,
        acceptedMimeTypes = listOf(MimeTypes.ANY),
        createMimeType = MimeTypes.BINARY,
        suggestedFileName = "",
        initialUri = null,
        localOnly = false,
        openableOnly = false,
        excludeSelf = false,
        prompt = null
    )
    private var requestedMimeMatcher: (String) -> Boolean = { true }
    private var initialized = false

    internal val pickerMode: PickerMode
        get() = request.mode

    private var currentStorageRoot: String? = null
    private var pendingLocalPath: String? = null
    private var currentSettings = FileManagerSettings()

    private val localCoordinator = LocalBrowserCoordinator<PickerBrowserItem>(
        appContext = appContext,
        modelScope = modelScope,
        fileRepository = fileRepository,
        storageRepository = storageRepository
    )

    private val searchHandler = PickerSearchHandler(
        appContext = appContext,
        modelScope = modelScope,
        uiState = _uiState,
        documentsRepository = documentsRepository,
        localSearchRepository = localSearchRepository,
        providerQueryRunner = providerQueryRunner,
        getRequest = { request },
        getSettings = { currentSettings },
        providerRootKey = ::providerRootKey
    )

    private val recentsHandler = PickerRecentsHandler(
        appContext = appContext,
        modelScope = modelScope,
        uiState = _uiState,
        documentsRepository = documentsRepository,
        localSearchRepository = localSearchRepository,
        providerQueryRunner = providerQueryRunner,
        getRequest = { request },
        getSettings = { currentSettings },
        providerRootKey = ::providerRootKey
    )

    private val providerHandler = PickerProviderHandler(
        appContext = appContext,
        modelScope = modelScope,
        uiState = _uiState,
        documentsRepository = documentsRepository,
        providerQueryRunner = providerQueryRunner,
        getRequest = { request },
        providerDocumentKey = ::providerDocumentKey,
        updateUiMetadata = ::updateUiMetadata
    )

    init {
        modelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                updateUiMetadata()
            }
        }
    }

    private var specialReturnSnapshot: BrowseSnapshot? = null
    private var initialLocationJob: Job? = null

    internal fun initialize(pickerRequest: PickerRequest) {
        if (initialized) return
        initialized = true
        request = pickerRequest
        requestedMimeMatcher = MimeTypes.matcher(pickerRequest.acceptedMimeTypes)
        openInitialLocation()
    }

    internal suspend fun createLocalFolder(parentPath: String, name: String): Result<String> = fileRepository.createFolder(parentPath, name)

    internal suspend fun createProviderDirectory(location: ProviderLocation, name: String): Result<ProviderDocumentInfo> =
        documentsRepository.createDirectory(location.root.authority, location.document.documentId, name)

    internal suspend fun createProviderDocument(location: ProviderLocation, name: String): Result<ProviderDocumentInfo> =
        documentsRepository.createDocument(
            location.root.authority,
            location.document.documentId,
            request.createMimeType,
            name
        )

    internal fun runGlobalSearch(query: String) {
        providerHandler.incrementNavigation()
        saveBrowseSnapshotIfNeeded()
        initialLocationJob?.cancel()
        currentStorageRoot = null
        searchHandler.runGlobalSearch(query) {
            providerHandler.stopObservingProviderDirectory()
        }
    }

    internal fun runGlobalRecents() {
        providerHandler.incrementNavigation()
        saveBrowseSnapshotIfNeeded()
        initialLocationJob?.cancel()
        currentStorageRoot = null
        recentsHandler.runGlobalRecents {
            providerHandler.stopObservingProviderDirectory()
        }
    }

    private fun saveBrowseSnapshotIfNeeded() {
        val state = _uiState.value
        if (state.displayMode == DisplayMode.BROWSE) {
            specialReturnSnapshot = BrowseSnapshot(
                localPath = state.currentLocalPath,
                storageRoot = currentStorageRoot,
                providerStack = state.providerStack,
                focusKey = state.focusedKey
            )
        }
    }

    internal fun restoreBrowseSnapshot() {
        val snapshot = specialReturnSnapshot ?: return
        specialReturnSnapshot = null
        initialLocationJob?.cancel()
        searchHandler.cancelSearch()
        recentsHandler.cancelRecents()
        
        if (snapshot.localPath != null) {
            openLocalDirectory(snapshot.localPath, snapshot.focusKey, true)
        } else if (snapshot.providerStack.isNotEmpty()) {
            val last = snapshot.providerStack.last()
            _uiState.update { it.copy(
                displayMode = DisplayMode.BROWSE, 
                currentLocalPath = null, 
                providerStack = snapshot.providerStack
            )}
            providerHandler.refreshProviderDirectory(last, snapshot.focusKey)
        } else {
            showSourceOverview(snapshot.focusKey)
        }
    }

    internal fun openProviderResultDirectory(item: PickerBrowserItem.ProviderDocument) {
        saveBrowseSnapshotIfNeeded()
        initialLocationJob?.cancel()
        searchHandler.cancelSearch()
        recentsHandler.cancelRecents()
        val loc = ProviderLocation(item.root, item.document, item.document.displayName)
        _uiState.update { it.copy(
            displayMode = DisplayMode.BROWSE, 
            currentLocalPath = null, 
            providerStack = listOf(loc)
        )}
        providerHandler.refreshProviderDirectory(loc)
    }

    private fun openInitialLocation() {
        val initialUri = request.initialUri ?: run {
            showSourceOverview()
            return
        }
        initialLocationJob?.cancel()
        _uiState.update { it.copy(providerLoading = true) }

        initialLocationJob = modelScope.launch {
            val localDir = withContext(Dispatchers.IO) { 
                FileUtil.runCancellable { resolveLocalInitialDirectory(initialUri) }.getOrNull() 
            }
            if (localDir != null) {
                _uiState.update { it.copy(providerLoading = false) }
                openLocalDirectory(localDir)
                return@launch
            }
            if (!documentsRepository.hasSystemDocumentAccess()) {
                _uiState.update { it.copy(providerLoading = false) }
                showSourceOverview()
                return@launch
            }
            val providerLocs = withContext(Dispatchers.IO) { 
                FileUtil.runCancellable { resolveProviderInitialLocation(initialUri) }.getOrNull() 
            }
            if (providerLocs.isNullOrEmpty()) {
                _uiState.update { it.copy(providerLoading = false) }
                showSourceOverview()
                return@launch
            }
            _uiState.update { it.copy(
                providerLoading = false, 
                displayMode = DisplayMode.BROWSE, 
                currentLocalPath = null, 
                providerStack = providerLocs
            )}
            providerHandler.refreshProviderDirectory(providerLocs.last(), initialDocumentId(initialUri))
        }
    }

    private suspend fun resolveLocalInitialDirectory(uri: Uri): String? {
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null).canonicalFile
        if (!file.exists()) return null
        val root = findStorageRoot(file.absolutePath) ?: return null
        return if (file.isDirectory) file.absolutePath else file.parentFile?.absolutePath ?: root
    }

    private suspend fun resolveProviderInitialLocation(uri: Uri): List<ProviderLocation>? {
        val pathInfo = documentsRepository.findDocumentPath(uri).getOrNull() ?: return null
        val rootResult = documentsRepository.queryRoots(
            request.acceptedMimeTypes,
            request.localOnly,
            requireCreate = false,
            excludeSelf = request.excludeSelf
        )
        val root = rootResult.roots.firstOrNull { it.authority == pathInfo.authority && it.rootId == pathInfo.rootId } ?: return null
        
        val stack = mutableListOf<ProviderLocation>()
        for (id in pathInfo.documentIds) {
            val doc = documentsRepository.queryDocument(root.authority, id, root.rootId).getOrNull() ?: break
            stack.add(ProviderLocation(root, doc, doc.displayName))
            if (id == pathInfo.documentIds.last()) break
        }
        return stack.takeIf { it.isNotEmpty() }
    }

    private fun initialDocumentId(uri: Uri): String? = 
        if (DocumentsContract.isDocumentUri(appContext, uri)) DocumentsContract.getDocumentId(uri) 
        else if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) 
        else null

    internal fun showSourceOverview(focusKey: String? = null) {
        providerHandler.incrementNavigation()
        initialLocationJob?.cancel()
        searchHandler.cancelSearch()
        recentsHandler.cancelRecents()
        providerHandler.stopObservingProviderDirectory()
        currentStorageRoot = null

        _uiState.update { it.copy(
            displayMode = DisplayMode.BROWSE,
            currentLocalPath = null,
            currentLocalTitle = appContext.getString(R.string.storage),
            currentLocalDirectoryWritable = false,
            currentLocalTreeSelectable = false,
            providerStack = emptyList(),
            pickerItems = emptyList(),
            providerLoading = true,
            providerInfoMessage = null,
            providerErrorMessage = null
        )}

        modelScope.launch {
            updateUiMetadata()
            val localResult = withContext(Dispatchers.IO) { 
                FileUtil.runCancellable { storageRepository.getStorages().map { PickerBrowserItem.Local(it) } } 
            }
            if (!isSourceOverview()) return@launch

            val local = localResult.getOrElse { error ->
                _uiState.update { it.copy(
                    providerLoading = false, 
                    providerErrorMessage = error.message ?: appContext.getString(R.string.storage_source_open_failed)
                )}
                return@launch
            }

            if (focusKey == null || local.any { it.key == focusKey }) {
                _uiState.update { it.copy(pickerItems = local) }
            }

            if (!documentsRepository.hasSystemDocumentAccess()) {
                _uiState.update { state -> state.copy(
                    focusTargetKey = focusKey?.takeIf { target -> local.any { item -> item.key == target } },
                    pickerItems = local,
                    providerLoading = false
                )}
                return@launch
            }

            val rootsResult = documentsRepository.queryRoots(
                request.acceptedMimeTypes,
                request.localOnly,
                pickerMode == PickerMode.CREATE_DOCUMENT,
                request.excludeSelf
            )
            if (!isSourceOverview()) return@launch

            val providerItems = rootsResult.roots.map { PickerBrowserItem.ProviderRoot(it) }
            val allItems = local + providerItems

            _uiState.update { state -> state.copy(
                pickerItems = allItems,
                focusTargetKey = focusKey?.takeIf { target -> allItems.any { it.key == target } },
                providerLoading = false,
                providerInfoMessage = rootsResult.errors.size.takeIf { it > 0 }?.let { errorCount ->
                    appContext.resources.getQuantityString(R.plurals.provider_load_errors, errorCount, errorCount)
                }
            )}
        }
    }

    private fun isSourceOverview(): Boolean = 
        _uiState.value.displayMode == DisplayMode.BROWSE && 
        _uiState.value.currentLocalPath == null && 
        _uiState.value.providerStack.isEmpty()

    internal fun openLocalDirectory(path: String, focusKey: String? = null, restoreCachedState: Boolean = false) {
        if (!hasAllFilesAccess()) {
            pendingLocalPath = path
            eventChannel.trySend(PickerUiEvent.RequestStorageAccess)
            return
        }
        pendingLocalPath = null
        providerHandler.incrementNavigation()
        initialLocationJob?.cancel()
        searchHandler.cancelSearch()
        recentsHandler.cancelRecents()

        currentStorageRoot = null
        modelScope.launch {
            val storageRoot = findStorageRoot(path)
            if (_uiState.value.currentLocalPath == path) {
                currentStorageRoot = storageRoot
                _uiState.update {
                    it.copy(
                        currentLocalTreeSelectable = isLocalTreeSelectable(
                            path = path,
                            storageRoot = storageRoot
                        )
                    )
                }
                updateUiMetadata()
            }
        }

        localCoordinator.loadDirectory(
            path = path,
            settings = currentSettings,
            restoreCachedState = restoreCachedState,
            isCurrentPath = { _uiState.value.displayMode == DisplayMode.BROWSE && _uiState.value.currentLocalPath == it },
            filter = { item ->
                when (pickerMode) {
                    PickerMode.OPEN_DOCUMENT, PickerMode.GET_CONTENT -> {
                        when (item) {
                            is BrowserItem.Folder -> PickerBrowserItem.Local(item)
                            is BrowserItem.File -> if (matchesRequestedMimeType(item.path)) PickerBrowserItem.Local(item) else null
                            else -> null
                        }
                    }
                    else -> if (item is BrowserItem.Folder) PickerBrowserItem.Local(item) else null
                }
            },
            onLoading = { cachedItems ->
                _uiState.update { it.copy(
                    displayMode = DisplayMode.BROWSE,
                    currentLocalPath = path,
                    providerStack = emptyList(),
                    pickerItems = cachedItems ?: emptyList(),
                    providerLoading = cachedItems == null
                )}
            },
            onLoaded = { items, title, metadata ->
                _uiState.update { state -> state.copy(
                    pickerItems = items,
                    currentLocalTitle = title,
                    currentLocalDirectoryWritable = (metadata["writable"] as? Boolean) ?: false,
                    currentLocalTreeSelectable = isLocalTreeSelectable(
                        path = path,
                        storageRoot = currentStorageRoot
                    ),
                    focusTargetKey = focusKey?.takeIf { target -> items.any { item -> item.key == target } },
                    providerLoading = false
                )}
            },
            onError = { message ->
                _uiState.update { it.copy(
                    providerLoading = false,
                    providerErrorMessage = message
                )}
            }
        )
    }

    internal fun localGridPosition(path: String?): TvGridPosition? = localCoordinator.getGridPosition(path)
    internal fun saveLocalGridPosition(path: String?, pos: TvGridPosition) = localCoordinator.saveGridPosition(path, pos)

    internal fun openProviderRoot(root: DocumentRootInfo) {
        saveBrowseSnapshotIfNeeded()
        initialLocationJob?.cancel()
        searchHandler.cancelSearch()
        recentsHandler.cancelRecents()
        providerHandler.openProviderRoot(root)
    }

    internal fun refreshProviderDirectory(location: ProviderLocation, focusKey: String? = null) {
        providerHandler.incrementNavigation()
        initialLocationJob?.cancel()
        searchHandler.cancelSearch()
        recentsHandler.cancelRecents()
        currentStorageRoot = null
        providerHandler.refreshProviderDirectory(location, focusKey)
    }

    internal fun retryCurrentProviderLocation() {
        _uiState.value.providerStack.lastOrNull()?.let { refreshProviderDirectory(it) } ?: showSourceOverview()
    }

    @Suppress("unused") // General reset for provider status messages
    internal fun clearProviderStatus() {
        _uiState.update { it.copy(providerErrorMessage = null, providerInfoMessage = null) }
    }

    internal fun navigateBack() {
        val state = _uiState.value
        if (state.displayMode != DisplayMode.BROWSE) {
            restoreBrowseSnapshot()
            return
        }
        if (state.providerStack.isNotEmpty()) {
            val newStack = state.providerStack.dropLast(1)
            if (newStack.isEmpty()) {
                showSourceOverview(providerRootKey(state.providerStack.first().root))
            } else {
                val last = newStack.last()
                _uiState.update { it.copy(providerStack = newStack) }
                providerHandler.refreshProviderDirectory(last, providerDocumentKey(state.providerStack.last().document))
            }
            return
        }
        val path = state.currentLocalPath ?: return
        modelScope.launch {
            if (isStorageRoot(path)) {
                showSourceOverview(localKey(path))
                return@launch
            }
            val parent = File(path).parentFile
            if (parent == null) {
                showSourceOverview()
            } else {
                openLocalDirectory(parent.absolutePath, localKey(path), true)
            }
        }
    }

    private fun updateUiMetadata() {
        _uiState.update { it.copy(
            title = calculateTitleInternal(), 
            canCreateFolder = _uiState.value.displayMode == DisplayMode.BROWSE && canCreateInCurrentLocation(), 
            primaryActionLabel = calculatePrimaryActionLabel()
        )}
    }

    private fun calculateTitleInternal(): String {
        val state = _uiState.value
        return when (state.displayMode) {
            DisplayMode.SEARCH -> appContext.getString(R.string.search_title_query, state.currentSearchQuery)
            DisplayMode.RECENTS -> appContext.getString(R.string.recents)
            else -> request.prompt
                ?: if (state.providerStack.isNotEmpty()) {
                    state.providerStack.last().document.displayName
                } else {
                    state.currentLocalTitle ?: appContext.getString(R.string.storage)
                }
        }
    }

    private fun calculatePrimaryActionLabel(): String? = when (pickerMode) {
        PickerMode.OPEN_DOCUMENT_TREE -> if (canSelectCurrentTree()) appContext.getString(R.string.select_this_folder) else null
        PickerMode.CREATE_DOCUMENT -> if (canCreateInCurrentLocation()) appContext.getString(R.string.save_here) else null
        else -> null
    }

    internal fun canCreateInCurrentLocation(): Boolean {
        val state = _uiState.value
        return if (state.providerStack.isNotEmpty()) state.providerStack.last().root.supportsCreate 
        else state.currentLocalPath?.let { File(it).canWrite() } ?: false
    }

    private fun canSelectCurrentTree(): Boolean {
        val state = _uiState.value
        return if (state.providerStack.isNotEmpty()) {
            val location = state.providerStack.last()
            location.root.supportsIsChild && !location.document.blocksOpenDocumentTree
        } else {
            state.currentLocalPath != null && state.currentLocalTreeSelectable
        }
    }

    internal fun currentTreeUri(): Uri? {
        val state = _uiState.value
        if (state.displayMode != DisplayMode.BROWSE || !canSelectCurrentTree()) return null
        return if (state.providerStack.isNotEmpty()) { 
            val loc = state.providerStack.last()
            DocumentsContract.buildTreeDocumentUri(loc.root.authority, loc.document.documentId) 
        }
        else state.currentLocalPath?.let { DocumentUriMapper.treeUri(appContext, it) }
    }

    internal fun uriForPickerItem(item: PickerBrowserItem): Uri? = when (item) {
        is PickerBrowserItem.Local -> DocumentUriMapper.documentUri(appContext, item.item.path)
        is PickerBrowserItem.ProviderDocument -> DocumentsContract.buildDocumentUri(item.document.authority, item.document.documentId)
        else -> null
    }

    internal suspend fun createLocalDocument(parentPath: String, name: String): Result<Uri> = withContext(Dispatchers.IO) {
        FileUtil.runCancellable {
            val parent = File(parentPath).canonicalFile
            require(parent.isDirectory && parent.canWrite()) {
                appContext.getString(R.string.target_read_only)
            }

            val cleanName = runCatching { FileUtil.validateFileName(name) }
                .getOrElse { throw IllegalArgumentException(appContext.getString(R.string.invalid_name)) }
            val file = File(parent, cleanName).canonicalFile

            require(file.parentFile == parent) {
                appContext.getString(R.string.invalid_name)
            }
            require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                appContext.getString(R.string.already_exists, cleanName)
            }
            check(file.createNewFile()) {
                appContext.getString(R.string.file_create_failed)
            }

            DocumentUriMapper.documentUri(appContext, file.absolutePath)
        }
    }

    internal fun resultGrantFlags(): Int = when (pickerMode) {
        PickerMode.GET_CONTENT ->
            Intent.FLAG_GRANT_READ_URI_PERMISSION

        PickerMode.OPEN_DOCUMENT ->
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        PickerMode.CREATE_DOCUMENT ->
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        PickerMode.OPEN_DOCUMENT_TREE ->
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
    private fun matchesRequestedMimeType(path: String): Boolean = requestedMimeMatcher(MimeTypes.forFileName(File(path).name))
    private fun samePath(p1: String, p2: String): Boolean = runCatching { File(p1).canonicalPath == File(p2).canonicalPath }.getOrDefault(p1 == p2)
    private fun isLocalTreeSelectable(path: String, storageRoot: String?): Boolean {
        if (FileUtil.isSafRestrictedPath(path)) return false
        val root = storageRoot ?: return false
        if (samePath(path, root)) return false
        return !samePath(path, File(root, "Download").absolutePath)
    }
    private suspend fun isStorageRoot(path: String): Boolean = storageRepository.getStorages().any { samePath(it.path, path) }
    private suspend fun findStorageRoot(path: String): String? = storageRepository.getStorages()
        .map { it.path }
        .firstOrNull { path == it || path.startsWith(it + File.separator) }

    internal fun localKey(path: String): String = "local:$path"
    private fun providerRootKey(root: DocumentRootInfo): String = "root:${root.authority}:${root.rootId}"
    internal fun providerDocumentKey(doc: ProviderDocumentInfo): String = "document:${doc.authority}:${doc.documentId}"

    internal fun setFocusedKey(key: String?) = _uiState.update { it.copy(focusedKey = key) }
    internal fun setFocusTargetKey(key: String?) = _uiState.update { it.copy(focusTargetKey = key) }
    internal fun dismissProviderError() = _uiState.update { it.copy(providerErrorMessage = null) }
    internal fun dismissProviderInfo() = _uiState.update { it.copy(providerInfoMessage = null) }
    internal fun finishWithUris(uris: List<Uri>) { if (uris.isNotEmpty()) eventChannel.trySend(PickerUiEvent.Finish(uris)) }
    internal fun cancelPicker() = eventChannel.trySend(PickerUiEvent.Cancel)
    internal fun startWatchingStorage() {
        storageRepository.startWatching {
            if (isSourceOverview()) {
                showSourceOverview(_uiState.value.focusedKey)
                return@startWatching
            }

            val activeRoot = currentStorageRoot ?: return@startWatching
            modelScope.launch {
                val stillMounted = storageRepository.getStorages().any {
                    samePath(it.path, activeRoot)
                }
                if (!stillMounted && samePath(currentStorageRoot ?: return@launch, activeRoot)) {
                    showSourceOverview()
                }
            }
        }
    }
    internal fun stopWatchingStorage() = storageRepository.stopWatching()
    internal fun resumeAfterStoragePermission() { pendingLocalPath?.let { if (hasAllFilesAccess()) openLocalDirectory(it) } }

    override fun onCleared() {
        providerHandler.stopObservingProviderDirectory()
        providerHandler.cancelProviderJobs()
        searchHandler.cancelSearch()
        recentsHandler.cancelRecents()
        initialLocationJob?.cancel()
        localCoordinator.clearCache()
        modelScope.cancel()
        storageRepository.stopWatching()
        eventChannel.close()
        super.onCleared()
    }

}
