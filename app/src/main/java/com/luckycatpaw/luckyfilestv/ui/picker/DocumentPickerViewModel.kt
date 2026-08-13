package com.luckycatpaw.luckyfilestv.ui.picker

import android.app.Application
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.repository.DocumentsProviderRepository
import com.luckycatpaw.luckyfilestv.data.repository.FileRepository
import com.luckycatpaw.luckyfilestv.data.repository.LocalFileSearchRepository
import com.luckycatpaw.luckyfilestv.data.repository.SettingsRepository
import com.luckycatpaw.luckyfilestv.data.repository.StorageRepository
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo
import com.luckycatpaw.luckyfilestv.data.provider.DocumentUriMapper
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import com.luckycatpaw.luckyfilestv.util.hasAllFilesAccess
import com.luckycatpaw.luckyfilestv.ui.picker.model.BrowseSnapshot
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerRequest
import com.luckycatpaw.luckyfilestv.ui.picker.model.ProviderLocation
import com.luckycatpaw.luckyfilestv.ui.picker.model.RecentEntry
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.LinkedHashMap

internal class DocumentPickerViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eventChannel = Channel<PickerUiEvent>(Channel.BUFFERED)

    internal val events = eventChannel.receiveAsFlow()
    private val storageRepository = StorageRepository(appContext)
    private val settingsRepository = SettingsRepository(appContext)
    private val documentsRepository = DocumentsProviderRepository(appContext)
    private val fileRepository = FileRepository(appContext)
    private val localSearchRepository = LocalFileSearchRepository(storageRepository)
    private val providerQueryRunner = ProviderQueryRunner(appContext)

    private val _uiState = MutableStateFlow(PickerUiState())
    internal val uiState = combine(
        _uiState,
        settingsRepository.settings
    ) { state, settings ->
        state.copy(settings = settings)
    }.stateIn(
        scope = modelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PickerUiState()
    )

    internal lateinit var request: PickerRequest
        private set
    private lateinit var requestedMimeMatcher: (String) -> Boolean

    private var initialized = false

    internal val pickerMode: PickerMode
        get() = request.mode

    private var currentStorageRoot: String? = null
    private var pendingLocalPath: String? = null
    private var providerRoots: List<DocumentRootInfo> = emptyList()

    private var currentSettings = FileManagerSettings()

    init {
        modelScope.launch {
            settingsRepository.settings.collect { settings ->
                if (settings != currentSettings) {
                    localDirectorySnapshots.clear()
                }
                currentSettings = settings
            }
        }
    }
    private var specialReturnSnapshot: BrowseSnapshot? = null

    private var providerObserver: ContentObserver? = null
    private var observedProviderUri: Uri? = null
    private var providerQueryJob: Job? = null
    private var providerRefreshJob: Job? = null
    private var specialQueryJob: Job? = null
    private var localQueryJob: Job? = null
    private var initialLocationJob: Job? = null
    private var providerNavigationJob: Job? = null
    private var providerNavigationGeneration = 0
    private val localDirectorySnapshots = object :
        LinkedHashMap<String, LocalDirectorySnapshot>(
            LOCAL_DIRECTORY_SNAPSHOT_LIMIT,
            0.75f,
            true
        ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LocalDirectorySnapshot>
        ): Boolean = size > LOCAL_DIRECTORY_SNAPSHOT_LIMIT
    }

    internal fun initialize(request: PickerRequest) {
        if (initialized) return

        initialized = true
        this.request = request
        requestedMimeMatcher = MimeTypes.matcher(request.acceptedMimeTypes)
        openInitialLocation()
    }

    internal suspend fun createLocalFolder(
        parentPath: String,
        name: String
    ): Result<String> = fileRepository.createFolder(parentPath, name)

    internal suspend fun createProviderDirectory(
        location: ProviderLocation,
        name: String
    ): Result<ProviderDocumentInfo> =
        documentsRepository.createDirectory(
            location.document.authority,
            location.document.documentId,
            name
        )

    internal suspend fun createProviderDocument(
        location: ProviderLocation,
        name: String
    ): Result<ProviderDocumentInfo> =
        documentsRepository.createDocument(
            location.document.authority,
            location.document.documentId,
            request.createMimeType,
            name
        )

    internal fun runGlobalSearch(query: String) {
        cancelProviderNavigation()
        saveBrowseSnapshotIfNeeded()
        initialLocationJob?.cancel()

        _uiState.update { it.copy(
            displayMode = DisplayMode.SEARCH,
            currentSearchQuery = query,
            currentLocalPath = null,
            currentLocalTitle = null,
            currentLocalDirectoryWritable = false,
            currentLocalTreeSelectable = false,
            providerStack = emptyList(),
            pickerItems = emptyList(),
            providerLoading = true,
            providerInfoMessage = appContext.getString(R.string.search_running),
            providerErrorMessage = null
        )}
        stopObservingProviderDirectory()
        specialQueryJob?.cancel()
        localQueryJob?.cancel()

        currentStorageRoot = null

        specialQueryJob = modelScope.launch {
            updateUiMetadata()

            val directoriesOnly =
                pickerMode == PickerMode.CREATE_DOCUMENT ||
                        pickerMode == PickerMode.OPEN_DOCUMENT_TREE

            val hasProviderAccess = documentsRepository.hasSystemDocumentAccess()
            val localResultsDeferred = async(Dispatchers.IO) {
                localSearchRepository.search(
                    query = query,
                    directoriesOnly = directoriesOnly,
                    settings = currentSettings,
                    acceptedMimeTypes = request.acceptedMimeTypes
                ).map(PickerBrowserItem::Local)
            }
            val rootsDeferred = if (hasProviderAccess) {
                async {
                    documentsRepository.queryRoots(
                        acceptedMimeTypes = request.acceptedMimeTypes,
                        localOnly = request.localOnly,
                        requireCreate = pickerMode == PickerMode.CREATE_DOCUMENT,
                        excludeSelf = true
                    )
                }
            } else {
                null
            }
            val localResults = localResultsDeferred.await()

            if (!isCurrentSearch(query)) {
                return@launch
            }

            val providerResults = linkedMapOf<String, List<PickerBrowserItem>>()
            publishSearchResults(
                query = query,
                localResults = localResults,
                providerResults = providerResults
            )

            if (!hasProviderAccess) {
                _uiState.update { it.copy(
                    providerLoading = false,
                    providerInfoMessage = if (it.pickerItems.isEmpty()) {
                        appContext.getString(R.string.no_search_results, query)
                    } else {
                        null
                    }
                )}
                return@launch
            }

            val rootsResult = requireNotNull(rootsDeferred).await()

            if (!isCurrentSearch(query)) return@launch

            providerRoots = rootsResult.roots
            var providerErrors = rootsResult.errors.size
            var loadingTimeouts = 0

            val searchableRoots = rootsResult.roots.filter { it.supportsSearch }

            val semaphore = Semaphore(MAX_PARALLEL_PROVIDER_QUERIES)

            coroutineScope {
                searchableRoots.map { root ->
                    launch {
                        semaphore.withPermit {
                            val outcome = providerQueryRunner.queryUntilSettled(
                                observedUri = DocumentsContract.buildSearchDocumentsUri(
                                    root.authority,
                                    root.rootId,
                                    query
                                ),
                                query = { signal ->
                                    documentsRepository.searchDocuments(
                                        root = root,
                                        query = query,
                                        acceptedMimeTypes = request.acceptedMimeTypes,
                                        directoriesOnly = directoriesOnly,
                                        openableOnly = request.openableOnly,
                                        cancellationSignal = signal
                                    )
                                }
                            ) { search ->
                                if (isCurrentSearch(query)) {
                                    providerResults[providerRootKey(root)] =
                                        search.documents.map {
                                            PickerBrowserItem.ProviderDocument(
                                                document = it,
                                                root = root
                                            )
                                        }

                                    publishSearchResults(
                                        query = query,
                                        localResults = localResults,
                                        providerResults = providerResults
                                    )

                                    _uiState.update { it.copy(
                                        providerInfoMessage = search.info
                                            ?: if (search.loading) {
                                                appContext.getString(R.string.providers_still_loading)
                                            } else {
                                                appContext.getString(R.string.search_running)
                                            }
                                    )}
                                }
                            }

                            if (isCurrentSearch(query)) {
                                if (outcome.failure != null || outcome.result?.error != null) {
                                    providerErrors++
                                }

                                if (outcome.loadingTimedOut) {
                                    loadingTimeouts++
                                }
                            }
                        }
                    }
                }.joinAll()
            }

            if (!isCurrentSearch(query)) return@launch

            _uiState.update { it.copy(
                providerLoading = false,
                providerInfoMessage = when {
                    providerErrors > 0 ->
                        appContext.getString(R.string.provider_search_errors, providerErrors)

                    it.pickerItems.isEmpty() ->
                        appContext.getString(R.string.no_search_results, query)

                    loadingTimeouts > 0 ->
                        appContext.getString(R.string.providers_still_loading)

                    else -> null
                }
            )}
        }
    }

    internal fun runGlobalRecents() {
        if (
            pickerMode != PickerMode.OPEN_DOCUMENT &&
            pickerMode != PickerMode.GET_CONTENT
        ) {
            return
        }

        cancelProviderNavigation()
        saveBrowseSnapshotIfNeeded()
        initialLocationJob?.cancel()

        _uiState.update { it.copy(
            displayMode = DisplayMode.RECENTS,
            currentLocalPath = null,
            currentLocalTitle = null,
            currentLocalDirectoryWritable = false,
            currentLocalTreeSelectable = false,
            providerStack = emptyList(),
            pickerItems = emptyList(),
            providerLoading = true,
            providerInfoMessage = appContext.getString(R.string.recents_loading),
            providerErrorMessage = null
        )}
        stopObservingProviderDirectory()
        specialQueryJob?.cancel()
        localQueryJob?.cancel()

        currentStorageRoot = null

        specialQueryJob = modelScope.launch {
            updateUiMetadata()

            val hasProviderAccess = documentsRepository.hasSystemDocumentAccess()
            val localEntriesDeferred = async(Dispatchers.IO) {
                localSearchRepository.loadRecents(
                    settings = currentSettings,
                    acceptedMimeTypes = request.acceptedMimeTypes
                ).map { recent ->
                    RecentEntry(
                        item = PickerBrowserItem.Local(recent.item),
                        modified = recent.modified
                    )
                }
            }
            val rootsDeferred = if (hasProviderAccess) {
                async {
                    documentsRepository.queryRoots(
                        acceptedMimeTypes = request.acceptedMimeTypes,
                        localOnly = request.localOnly,
                        requireCreate = false,
                        excludeSelf = true
                    )
                }
            } else {
                null
            }
            val localEntries = localEntriesDeferred.await()

            if (_uiState.value.displayMode != DisplayMode.RECENTS) {
                return@launch
            }

            val providerEntries = linkedMapOf<String, List<RecentEntry>>()
            publishRecentResults(
                localEntries = localEntries,
                providerEntries = providerEntries
            )

            if (!hasProviderAccess) {
                _uiState.update { it.copy(
                    providerLoading = false,
                    providerInfoMessage = if (it.pickerItems.isEmpty()) {
                        appContext.getString(R.string.no_recent_files)
                    } else {
                        null
                    }
                )}
                return@launch
            }

            val rootsResult = requireNotNull(rootsDeferred).await()

            if (_uiState.value.displayMode != DisplayMode.RECENTS) return@launch

            providerRoots = rootsResult.roots
            var providerErrors = rootsResult.errors.size
            var loadingTimeouts = 0

            val recentRoots = rootsResult.roots.filter {
                it.supportsRecents
            }

            val semaphore = Semaphore(MAX_PARALLEL_PROVIDER_QUERIES)

            coroutineScope {
                recentRoots.map { root ->
                    launch {
                        semaphore.withPermit {
                            val outcome = providerQueryRunner.queryUntilSettled(
                                observedUri = DocumentsContract.buildRecentDocumentsUri(
                                    root.authority,
                                    root.rootId
                                ),
                                query = { signal ->
                                    documentsRepository.queryRecentDocuments(
                                        root = root,
                                        acceptedMimeTypes = request.acceptedMimeTypes,
                                        openableOnly = request.openableOnly,
                                        cancellationSignal = signal
                                    )
                                }
                            ) { recent ->
                                if (_uiState.value.displayMode == DisplayMode.RECENTS) {
                                    providerEntries[providerRootKey(root)] =
                                        recent.documents
                                            .asSequence()
                                            .filterNot { it.isDirectory }
                                            .map { document ->
                                                RecentEntry(
                                                    item = PickerBrowserItem.ProviderDocument(
                                                        document = document,
                                                        root = root
                                                    ),
                                                    modified = document.lastModified ?: 0L
                                                )
                                            }
                                            .toList()

                                    publishRecentResults(
                                        localEntries = localEntries,
                                        providerEntries = providerEntries
                                    )

                                    _uiState.update { it.copy(
                                        providerInfoMessage = recent.info
                                            ?: if (recent.loading) {
                                                appContext.getString(R.string.providers_still_loading)
                                            } else {
                                                appContext.getString(R.string.recents_loading)
                                            }
                                    )}
                                }
                            }

                            if (_uiState.value.displayMode == DisplayMode.RECENTS) {
                                if (outcome.failure != null || outcome.result?.error != null) {
                                    providerErrors++
                                }

                                if (outcome.loadingTimedOut) {
                                    loadingTimeouts++
                                }
                            }
                        }
                    }
                }.joinAll()
            }

            if (_uiState.value.displayMode == DisplayMode.RECENTS) return@launch

            _uiState.update { it.copy(
                providerLoading = false,
                providerInfoMessage = when {
                    providerErrors > 0 ->
                        appContext.getString(R.string.provider_load_errors, providerErrors)

                    it.pickerItems.isEmpty() ->
                        appContext.getString(R.string.no_recent_files)

                    loadingTimeouts > 0 ->
                        appContext.getString(R.string.providers_still_loading)

                    else -> null
                }
            )}
        }
    }

    private fun publishSearchResults(
        query: String,
        localResults: List<PickerBrowserItem>,
        providerResults: Map<String, List<PickerBrowserItem>>
    ) {
        _uiState.update { it.copy(
            pickerItems = (localResults + providerResults.values.flatten())
                .distinctBy { item -> item.key }
                .map { item ->
                    RankedSearchItem(
                        item = item,
                        rank = searchRank(item.name, query)
                    )
                }
                .sortedWith(
                    compareBy<RankedSearchItem> { item ->
                        item.rank
                    }.thenBy { item ->
                        !item.item.isDirectory
                    }.thenBy(
                        String.CASE_INSENSITIVE_ORDER
                    ) { item ->
                        item.item.name
                    }
                )
                .take(MAX_SEARCH_RESULTS)
                .map { item -> item.item }
        )}
    }

    private fun publishRecentResults(
        localEntries: List<RecentEntry>,
        providerEntries: Map<String, List<RecentEntry>>
    ) {
        _uiState.update { it.copy(
            pickerItems = (localEntries + providerEntries.values.flatten())
                .sortedByDescending { it.modified }
                .distinctBy { it.item.key }
                .take(MAX_RECENT_RESULTS)
                .map { it.item }
        )}
    }

    private fun isCurrentSearch(query: String): Boolean {
        val state = _uiState.value
        return state.displayMode == DisplayMode.SEARCH &&
                state.currentSearchQuery == query
    }

    private fun searchRank(
        name: String,
        query: String
    ): Int {
        return when {
            name.equals(query, ignoreCase = true) -> 0
            name.startsWith(query, ignoreCase = true) -> 1
            else -> 2
        }
    }

    private data class RankedSearchItem(
        val item: PickerBrowserItem,
        val rank: Int
    )

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
        cancelProviderNavigation()
        specialQueryJob?.cancel()
        clearProviderStatus()

        val snapshot = specialReturnSnapshot
        specialReturnSnapshot = null
        _uiState.update { it.copy(displayMode = DisplayMode.BROWSE) }

        modelScope.launch {
            when {
                snapshot?.localPath != null -> {
                    openLocalDirectory(
                        snapshot.localPath,
                        snapshot.focusKey
                    )
                }

                snapshot != null && snapshot.providerStack.isNotEmpty() -> {
                    _uiState.update { it.copy(
                        currentLocalPath = null,
                        currentLocalTitle = null,
                        currentLocalDirectoryWritable = false,
                        currentLocalTreeSelectable = false,
                        providerStack = snapshot.providerStack
                    )}
                    currentStorageRoot = null

                    refreshProviderDirectory(
                        location = snapshot.providerStack.last(),
                        focusKey = snapshot.focusKey
                    )
                }

                else -> {
                    showSourceOverview(
                        focusKey = snapshot?.focusKey
                    )
                }
            }
        }
    }

    internal fun openProviderResultDirectory(
        item: PickerBrowserItem.ProviderDocument
    ) {
        cancelProviderNavigation()
        specialQueryJob?.cancel()
        stopObservingProviderDirectory()
        clearProviderStatus()
        specialReturnSnapshot = null
        _uiState.update { it.copy(
            displayMode = DisplayMode.BROWSE,
            providerLoading = true
        )}
        val generation = providerNavigationGeneration

        providerNavigationJob = modelScope.launch {
            val rootResult = try {
                documentsRepository.queryRootDocument(item.root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }

            if (!isCurrentProviderNavigation(generation)) return@launch

            val rootDocument = rootResult.getOrNull()

            if (rootDocument == null) {
                _uiState.update { it.copy(
                    providerLoading = false,
                    providerErrorMessage = rootResult.exceptionOrNull()?.message
                        ?: appContext.getString(R.string.storage_source_open_failed)
                )}
                return@launch
            }

            val rootLocation = ProviderLocation(
                root = item.root,
                document = rootDocument,
                title = item.root.title
            )

            val newStack =
                if (
                    rootDocument.documentId ==
                    item.document.documentId
                ) {
                    listOf(rootLocation)
                } else {
                    listOf(
                        rootLocation,
                        ProviderLocation(
                            root = item.root,
                            document = item.document,
                            title = item.document.displayName
                        )
                    )
                }

            _uiState.update { it.copy(
                providerStack = newStack,
                currentLocalPath = null,
                currentLocalTitle = null,
                currentLocalDirectoryWritable = false,
                currentLocalTreeSelectable = false
            )}
            currentStorageRoot = null

            refreshProviderDirectory(
                newStack.last()
            )
        }
    }

    private fun openInitialLocation() {
        val initialUri = request.initialUri ?: run {
            showSourceOverview()
            return
        }

        initialLocationJob?.cancel()
        _uiState.update { it.copy(providerLoading = true) }

        initialLocationJob = modelScope.launch {
            val localDirectory = withContext(Dispatchers.IO) {
                cancellableOrNull {
                    resolveLocalInitialDirectory(initialUri)
                }
            }

            if (localDirectory != null) {
                initialLocationJob = null
                _uiState.update { it.copy(providerLoading = false) }
                openLocalDirectory(localDirectory)
                return@launch
            }

            if (!documentsRepository.hasSystemDocumentAccess()) {
                initialLocationJob = null
                _uiState.update { it.copy(providerLoading = false) }
                showSourceOverview()
                return@launch
            }

            val providerLocations = withContext(Dispatchers.IO) {
                cancellableOrNull {
                    resolveProviderInitialLocation(initialUri)
                }
            }

            if (providerLocations.isNullOrEmpty()) {
                initialLocationJob = null
                _uiState.update { it.copy(providerLoading = false) }
                showSourceOverview()
                return@launch
            }

            initialLocationJob = null
            _uiState.update { it.copy(
                displayMode = DisplayMode.BROWSE,
                currentLocalPath = null,
                currentLocalTitle = null,
                currentLocalDirectoryWritable = false,
                currentLocalTreeSelectable = false,
                providerStack = providerLocations
            )}
            currentStorageRoot = null

            refreshProviderDirectory(providerLocations.last())
        }
    }

    private suspend fun resolveLocalInitialDirectory(uri: Uri): String? {
        val localPath = DocumentUriMapper.pathFromUri(
            appContext,
            uri
        ) ?: return null

        var candidate = runCatching {
            File(localPath).canonicalFile
        }.getOrNull() ?: return null

        if (!candidate.exists()) return null

        if (candidate.isFile) {
            candidate = candidate.parentFile?.canonicalFile ?: return null
        }

        val path = candidate.absolutePath
        val isManaged = isManagedPath(path)
        val isRestricted = isSafRestrictedPath(path)

        return path.takeIf {
            candidate.isDirectory && isManaged && !isRestricted
        }
    }

    private suspend fun resolveProviderInitialLocation(
        uri: Uri
    ): List<ProviderLocation>? {
        val authority = uri.authority ?: return null

        val roots = documentsRepository.queryRoots(
            acceptedMimeTypes = request.acceptedMimeTypes,
            localOnly = request.localOnly,
            requireCreate = pickerMode == PickerMode.CREATE_DOCUMENT,
            excludeSelf = true
        ).roots.filter {
            it.authority == authority
        }

        if (roots.isEmpty()) return null

        val initialDocumentId = initialDocumentId(uri) ?: return null

        var resolvedPath: Pair<DocumentRootInfo, List<String>>? = null
        for (root in roots) {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                authority,
                root.documentId
            )

            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                initialDocumentId
            )

            val pathResult = documentsRepository.findDocumentPath(documentUri)
            val documentIds = pathResult.getOrNull()?.documentIds

            if (documentIds != null &&
                documentIds.firstOrNull() == root.documentId &&
                documentIds.lastOrNull() == initialDocumentId) {
                resolvedPath = root to documentIds
                break
            }
        }

        val root = resolvedPath?.first
            ?: roots.firstOrNull { it.documentId == initialDocumentId }
            ?: roots.firstOrNull { candidate ->
                candidate.supportsIsChild &&
                        documentsRepository.isChildDocument(
                            candidate,
                            initialDocumentId
                        ).getOrDefault(false)
            }
            ?: roots.singleOrNull()
            ?: return null

        val documentIds = mutableListOf(root.documentId).apply {
            resolvedPath?.second.orEmpty().forEach { documentId ->
                if (documentId != root.documentId) {
                    add(documentId)
                }
            }

            if (size == 1 && initialDocumentId != root.documentId) {
                add(initialDocumentId)
            }
        }.distinct()

        val documents = mutableListOf<ProviderDocumentInfo>()

        documentIds.forEachIndexed { index, documentId ->
            val document = documentsRepository.queryDocument(
                authority = authority,
                documentId = documentId,
                parentDocumentId = documentIds.getOrNull(index - 1)
            ).getOrNull() ?: return null

            documents += document
        }

        if (documents.lastOrNull()?.isDirectory == false) {
            documents.removeAt(documents.lastIndex)
        }

        if (documents.isEmpty()) return null

        return documents.mapIndexed { index, document ->
            ProviderLocation(
                root = root,
                document = document,
                title = if (index == 0) {
                    root.title
                } else {
                    document.displayName
                }
            )
        }
    }

    private fun initialDocumentId(uri: Uri): String? {
        return runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull()
    }

    private fun showSourceOverview(
        focusKey: String? = null
    ) {
        cancelProviderNavigation()
        initialLocationJob?.cancel()
        initialLocationJob = null
        specialQueryJob?.cancel()
        localQueryJob?.cancel()
        specialReturnSnapshot = null

        stopObservingProviderDirectory()
        clearProviderStatus()

        _uiState.update { it.copy(
            displayMode = DisplayMode.BROWSE,
            currentLocalPath = null,
            currentLocalTitle = null,
            currentLocalDirectoryWritable = false,
            currentLocalTreeSelectable = false,
            providerStack = emptyList(),
            focusTargetKey = focusKey,
            pickerItems = emptyList(),
            providerLoading = true
        )}
        pendingLocalPath = null
        currentStorageRoot = null

        providerQueryJob = modelScope.launch {
            updateUiMetadata()

            val localResult = cancellableResult {
                storageRepository.getStorages().map {
                    PickerBrowserItem.Local(it)
                }
            }

            if (!isSourceOverview()) return@launch

            val local = localResult.getOrElse { error ->
                _uiState.update { it.copy(
                    providerLoading = false,
                    providerErrorMessage = error.message ?: appContext.getString(R.string.storage_source_open_failed)
                )}
                return@launch
            }

            if (
                focusKey == null ||
                local.any { it.key == focusKey }
            ) {
                _uiState.update { it.copy(pickerItems = local) }
            }

            if (!documentsRepository.hasSystemDocumentAccess()) {
                providerRoots = emptyList()
                _uiState.update { it.copy(
                    focusTargetKey = focusKey?.takeIf { target ->
                        local.any { it.key == target }
                    },
                    pickerItems = local,
                    providerLoading = false
                )}
                return@launch
            }

            val result = documentsRepository.queryRoots(
                acceptedMimeTypes = request.acceptedMimeTypes,
                localOnly = request.localOnly,
                requireCreate =
                    pickerMode == PickerMode.CREATE_DOCUMENT,
                excludeSelf = true
            )

            if (!isSourceOverview()) return@launch

            providerRoots = result.roots
            val sourceItems = local + result.roots.map {
                PickerBrowserItem.ProviderRoot(it)
            }
            _uiState.update { it.copy(
                focusTargetKey = focusKey?.takeIf { target ->
                    sourceItems.any { it.key == target }
                },
                pickerItems = sourceItems,
                providerLoading = false,
                providerInfoMessage = if (result.errors.isNotEmpty()) {
                    appContext.getString(
                        R.string.provider_load_errors,
                        result.errors.size
                    )
                } else {
                    it.providerInfoMessage
                }
            )}
        }
    }

    internal fun openLocalDirectory(
        path: String,
        focusKey: String? = null,
        restoreCachedState: Boolean = false
    ) {
        cancelProviderNavigation()
        initialLocationJob?.cancel()
        initialLocationJob = null
        specialQueryJob?.cancel()
        specialReturnSnapshot = null

        stopObservingProviderDirectory()
        clearProviderStatus()

        if (!hasAllFilesAccess()) {
            pendingLocalPath = path
            eventChannel.trySend(PickerUiEvent.RequestStorageAccess)
            return
        }

        modelScope.launch {
            if (!isManagedPath(path)) {
                showSourceOverview()
                return@launch
            }

            if (isSafRestrictedPath(path)) {
                return@launch
            }

            pendingLocalPath = null
            val enteringNewDirectory = _uiState.value.currentLocalPath != path
            val cachedSnapshot = localDirectorySnapshots[path]?.let { snapshot ->
                // A directory opened from the grid starts at its first item. Cached
                // scroll state is restored only while navigating back to the parent.
                if (enteringNewDirectory && !restoreCachedState) {
                    snapshot.copy(
                        gridPosition = TvGridPosition()
                    ).also { resetSnapshot ->
                        localDirectorySnapshots[path] = resetSnapshot
                    }
                } else {
                    snapshot
                }
            }

            val effectiveFocusKey = if (
                restoreCachedState && cachedSnapshot == null
            ) {
                null
            } else {
                focusKey
            }

            currentStorageRoot = findStorageRoot(path)

            _uiState.update { it.copy(
                displayMode = DisplayMode.BROWSE,
                providerStack = emptyList(),
                currentLocalPath = path,
                currentLocalTitle = cachedSnapshot?.title ?: (File(path).name.takeIf { it.isNotBlank() } ?: path),
                currentLocalDirectoryWritable = cachedSnapshot?.writable ?: false,
                currentLocalTreeSelectable = cachedSnapshot?.treeSelectable ?: false,
                focusTargetKey = effectiveFocusKey,
                pickerItems = cachedSnapshot?.items ?: emptyList(),
                providerLoading = cachedSnapshot?.items == null
            )}

            refreshLocalDirectory(path, effectiveFocusKey)
        }
    }

    internal fun refreshLocalDirectory(
        path: String,
        focusKey: String? = null
    ) {
        localQueryJob?.cancel()
        _uiState.update { it.copy(focusTargetKey = focusKey) }
        val settings = currentSettings
        val cachedSnapshot = localDirectorySnapshots[path]

        if (cachedSnapshot?.items != null) {
            publishLocalDirectorySnapshot(
                path = path,
                focusKey = focusKey,
                snapshot = cachedSnapshot
            )
        } else {
            _uiState.update { it.copy(providerLoading = true) }
        }

        localQueryJob = modelScope.launch {
            updateUiMetadata()

            val snapshotResult = cancellableResult {
                val items = fileRepository.getItems(
                    path = path,
                    hideFolderJpg = settings.hideFolderJpg,
                    sortMode = settings.sortMode,
                    sortAscending = settings.sortAscending,
                    foldersFirst = settings.foldersFirst
                ).getOrThrow().filterNot {
                    isSafRestrictedPath(it.path)
                }.filter { item ->
                    when (pickerMode) {
                        PickerMode.OPEN_DOCUMENT,
                        PickerMode.GET_CONTENT -> when (item) {
                            is BrowserItem.Folder -> true
                            is BrowserItem.File ->
                                matchesRequestedMimeType(item.path)
                            else -> true
                        }

                        else -> item is BrowserItem.Folder
                    }
                }.map {
                    PickerBrowserItem.Local(it)
                }

                val title = storageRepository.getStorages()
                    .firstOrNull { storage ->
                        samePath(storage.path, path)
                    }
                    ?.name
                    ?: File(path).name.takeIf { it.isNotBlank() }
                    ?: path

                LocalDirectorySnapshot(
                    items = items,
                    title = title,
                    writable = isDirectoryWritable(path),
                    treeSelectable = isLocalTreeSelectionAllowed(path)
                )
            }

            if (!isCurrentLocalDirectory(path)) {
                return@launch
            }

            val snapshot = snapshotResult.getOrElse { error ->
                _uiState.update { it.copy(
                    providerLoading = false,
                    providerErrorMessage = error.message ?: appContext.getString(R.string.folder_load_failed)
                )}
                return@launch
            }

            publishLocalDirectorySnapshot(path, focusKey, snapshot)
        }
    }

    private fun isCurrentLocalDirectory(path: String): Boolean {
        val state = _uiState.value
        return state.displayMode == DisplayMode.BROWSE &&
                state.currentLocalPath == path
    }

    private fun publishLocalDirectorySnapshot(
        path: String,
        focusKey: String?,
        snapshot: LocalDirectorySnapshot
    ) {
        if (!isCurrentLocalDirectory(path)) {
            return
        }

        val displayItems = snapshot.items.orEmpty()
        val cachedSnapshot = snapshot.copy(
            items = snapshot.items?.takeIf {
                it.size <= MAX_CACHED_ITEMS_PER_DIRECTORY
            },
            gridPosition = localDirectorySnapshots[path]
                ?.gridPosition
                ?: snapshot.gridPosition
        )
        localDirectorySnapshots[path] = cachedSnapshot

        _uiState.update { it.copy(
            focusTargetKey = focusKey?.takeIf { target ->
                displayItems.any { it.key == target }
            },
            pickerItems = displayItems,
            currentLocalTitle = cachedSnapshot.title,
            currentLocalDirectoryWritable = cachedSnapshot.writable,
            currentLocalTreeSelectable = cachedSnapshot.treeSelectable,
            providerLoading = false
        )}
    }

    internal fun localGridPosition(path: String?): TvGridPosition? {
        return path?.let { localDirectorySnapshots[it]?.gridPosition }
    }

    internal fun saveLocalGridPosition(
        path: String?,
        position: TvGridPosition
    ) {
        val directoryPath = path ?: return
        val snapshot = localDirectorySnapshots[directoryPath] ?: return
        localDirectorySnapshots[directoryPath] = snapshot.copy(
            gridPosition = position
        )
    }

    internal fun openProviderRoot(root: DocumentRootInfo) {
        cancelProviderNavigation()
        initialLocationJob?.cancel()
        initialLocationJob = null
        specialQueryJob?.cancel()
        localQueryJob?.cancel()
        specialReturnSnapshot = null
        stopObservingProviderDirectory()
        clearProviderStatus()
        _uiState.update { it.copy(
            displayMode = DisplayMode.BROWSE,
            providerLoading = true
        )}
        val generation = providerNavigationGeneration

        providerNavigationJob = modelScope.launch {
            val rootResult = try {
                documentsRepository.queryRootDocument(root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }

            if (!isCurrentProviderNavigation(generation)) return@launch

            rootResult
                .onSuccess { document ->
                    val location = ProviderLocation(
                        root,
                        document,
                        root.title
                    )

                    _uiState.update { it.copy(
                        currentLocalPath = null,
                        currentLocalTitle = null,
                        currentLocalDirectoryWritable = false,
                        currentLocalTreeSelectable = false,
                        providerStack = listOf(location)
                    )}
                    currentStorageRoot = null
                    refreshProviderDirectory(location)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(
                        providerLoading = false,
                        providerErrorMessage = error.message ?: appContext.getString(R.string.provider_open_failed)
                    )}
                }
        }
    }

    private fun cancelProviderNavigation() {
        providerNavigationGeneration++
        providerNavigationJob?.cancel()
        providerNavigationJob = null
    }

    private fun isCurrentProviderNavigation(generation: Int): Boolean {
        return generation == providerNavigationGeneration
    }

    internal fun refreshProviderDirectory(
        location: ProviderLocation,
        focusKey: String? = null
    ) {
        observeProviderDirectory(location)
        providerQueryJob?.cancel()
        _uiState.update { it.copy(focusTargetKey = focusKey) }

        providerQueryJob = modelScope.launch {
            _uiState.update { it.copy(providerLoading = true) }
            updateUiMetadata()

            val outcome = providerQueryRunner.queryUntilSettled(
                observedUri = DocumentsContract.buildChildDocumentsUri(
                    location.document.authority,
                    location.document.documentId
                ),
                query = { signal ->
                    documentsRepository.queryChildren(
                        authority = location.document.authority,
                        parentDocumentId = location.document.documentId,
                        acceptedMimeTypes = request.acceptedMimeTypes,
                        directoriesOnly =
                            pickerMode == PickerMode.CREATE_DOCUMENT ||
                                    pickerMode == PickerMode.OPEN_DOCUMENT_TREE,
                        openableOnly = request.openableOnly,
                        cancellationSignal = signal
                    )
                },
                onUpdate = { children ->
                    if (isCurrentProviderLocation(location)) {
                        _uiState.update { it.copy(
                            pickerItems = children.documents.map { doc ->
                                PickerBrowserItem.ProviderDocument(
                                    document = doc,
                                    root = location.root
                                )
                            },
                            providerLoading = children.loading,
                            providerInfoMessage = children.info,
                            providerErrorMessage = children.error
                        )}
                    }
                }
            )

            if (!isCurrentProviderLocation(location)) return@launch

            if (
                focusKey != null &&
                _uiState.value.pickerItems.none { it.key == focusKey }
            ) {
                _uiState.update { it.copy(focusTargetKey = null) }
            }

            when {
                outcome.failure != null -> {
                    _uiState.update { it.copy(
                        providerLoading = false,
                        providerErrorMessage = outcome.failure.message ?: appContext.getString(R.string.folder_load_failed)
                    )}
                }

                outcome.loadingTimedOut -> {
                    _uiState.update { it.copy(
                        providerLoading = false,
                        providerInfoMessage = appContext.getString(R.string.providers_still_loading)
                    )}
                }

                else -> {
                    _uiState.update { it.copy(providerLoading = false) }
                }
            }
        }
    }

    private fun isCurrentProviderLocation(location: ProviderLocation): Boolean {
        val state = _uiState.value
        val current: ProviderLocation = state.providerStack.lastOrNull() ?: return false

        return state.displayMode == DisplayMode.BROWSE &&
                current.root.authority == location.root.authority &&
                current.document.documentId == location.document.documentId
    }

    private fun observeProviderDirectory(location: ProviderLocation) {
        val uri = DocumentsContract.buildChildDocumentsUri(
            location.document.authority,
            location.document.documentId
        )

        if (providerObserver != null && observedProviderUri == uri) {
            return
        }

        stopObservingProviderDirectory()

        val observer = object : ContentObserver(
            Handler(Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                scheduleProviderRefresh()
            }

            override fun onChange(
                selfChange: Boolean,
                uri: Uri?
            ) {
                scheduleProviderRefresh()
            }
        }

        runCatching {
            appContext.contentResolver.registerContentObserver(
                uri,
                false,
                observer
            )

            providerObserver = observer
            observedProviderUri = uri
        }
    }

    private fun scheduleProviderRefresh() {
        providerRefreshJob?.cancel()

        providerRefreshJob = modelScope.launch {
            delay(120)

            _uiState.value.providerStack.lastOrNull()?.let {
                refreshProviderDirectory(
                    it,
                    _uiState.value.focusedKey
                )
            }
        }
    }

    private fun stopObservingProviderDirectory() {
        providerObserver?.let {
            runCatching {
                appContext.contentResolver.unregisterContentObserver(it)
            }
        }

        providerObserver = null
        observedProviderUri = null
        providerRefreshJob?.cancel()
        providerRefreshJob = null
    }

    internal fun retryCurrentProviderLocation() {
        _uiState.update { it.copy(providerErrorMessage = null) }

        _uiState.value.providerStack.lastOrNull()?.let {
            refreshProviderDirectory(
                it,
                _uiState.value.focusedKey
            )
        } ?: showSourceOverview(_uiState.value.focusedKey)
    }

    private fun clearProviderStatus() {
        _uiState.update { it.copy(
            providerLoading = false,
            providerInfoMessage = null,
            providerErrorMessage = null
        )}
        providerQueryJob?.cancel()
        providerQueryJob = null
    }

    internal fun navigateBack() {
        cancelProviderNavigation()
        val state = _uiState.value
        val localPath = state.currentLocalPath

        modelScope.launch {
            when {
                localPath != null -> {
                    if (isStorageRoot(localPath)) {
                        showSourceOverview(localKey(localPath))
                        return@launch
                    }

                    val parent = File(localPath).parentFile?.canonicalFile

                    if (
                        parent == null ||
                        !isManagedPath(parent.absolutePath)
                    ) {
                        showSourceOverview()
                    } else {
                        openLocalDirectory(
                            parent.absolutePath,
                            localKey(localPath),
                            restoreCachedState = true
                        )
                    }
                }

                state.providerStack.isNotEmpty() -> {
                    if (state.providerStack.size == 1) {
                        showSourceOverview(
                            providerRootKey(
                                (state.providerStack.first() as ProviderLocation).root
                            )
                        )
                    } else {
                        val leaving = state.providerStack.last() as ProviderLocation
                        val newStack = state.providerStack.dropLast(1)
                        _uiState.update { it.copy(providerStack = newStack) }

                        refreshProviderDirectory(
                            newStack.last() as ProviderLocation,
                            providerDocumentKey(
                                leaving.document
                            )
                        )
                    }
                }

                else -> cancelPicker()
            }
        }
    }

    private suspend fun updateUiMetadata() {
        val browsing = _uiState.value.displayMode == DisplayMode.BROWSE
        val canCreateFolder = browsing && canCreateFolderInternal()
        val primaryActionLabel = calculatePrimaryActionLabel()
        val title = calculateTitleInternal()

        _uiState.update { it.copy(
            canCreateFolder = canCreateFolder,
            primaryActionLabel = primaryActionLabel,
            title = title
        )}
    }

    private suspend fun calculateTitleInternal(): String {
        val state = _uiState.value
        return when (state.displayMode) {
            DisplayMode.SEARCH ->
                appContext.getString(R.string.search_title_query, state.currentSearchQuery)

            DisplayMode.RECENTS ->
                appContext.getString(R.string.recents)

            DisplayMode.BROWSE -> {
                state.currentLocalPath?.let {
                    state.currentLocalTitle
                        ?: (File(it).name.takeIf { name -> name.isNotBlank() } ?: it)
                } ?: state.providerStack.lastOrNull()?.title
                ?: request.prompt
                ?: when {
                    request.allowMultiple -> appContext.getString(R.string.picker_title_multiple)
                    pickerMode == PickerMode.CREATE_DOCUMENT -> appContext.getString(R.string.picker_title_create)
                    pickerMode == PickerMode.OPEN_DOCUMENT_TREE -> appContext.getString(R.string.picker_title_tree)
                    pickerMode == PickerMode.GET_CONTENT -> appContext.getString(R.string.picker_title_get_content)
                    else -> appContext.getString(R.string.picker_title_document)
                }
            }
        }
    }

    private fun canCreateFolderInternal(): Boolean {
        val state = _uiState.value
        return when {
            pickerMode != PickerMode.CREATE_DOCUMENT &&
                    pickerMode != PickerMode.OPEN_DOCUMENT_TREE -> false

            state.currentLocalPath != null ->
                state.currentLocalDirectoryWritable

            state.providerStack.isNotEmpty() ->
                state.providerStack.last().document.supportsCreate

            else -> false
        }
    }

    private fun calculatePrimaryActionLabel(): String? {
        return null
    }

    internal fun canCreateInCurrentLocation(): Boolean {
        if (pickerMode != PickerMode.CREATE_DOCUMENT) return false

        val state = _uiState.value
        if (state.currentLocalPath != null) {
            return state.currentLocalDirectoryWritable
        }

        val location = state.providerStack.lastOrNull()
        return location?.document?.supportsCreate == true
    }

    internal fun canSelectCurrentTree(): Boolean {
        if (pickerMode != PickerMode.OPEN_DOCUMENT_TREE) return false

        val state = _uiState.value
        if (state.currentLocalPath != null) {
            return state.currentLocalDirectoryWritable
        }

        val location = state.providerStack.lastOrNull()
        return location?.document?.blocksOpenDocumentTree == false
    }

    internal fun currentTreeUri(): Uri? {
        if (!canSelectCurrentTree()) return null

        val state = _uiState.value
        state.currentLocalPath?.let {
            return DocumentUriMapper.treeUri(
                appContext,
                it
            )
        }

        val location = state.providerStack.lastOrNull()
        if (location != null) {
            return documentsRepository.treeUri(
                location.document.authority,
                location.document.documentId
            )
        }

        return null
    }

    internal fun uriForPickerItem(
        item: PickerBrowserItem
    ): Uri? {
        return when (item) {
            is PickerBrowserItem.Local -> when (val local = item.item) {
                is BrowserItem.File ->
                    DocumentUriMapper.documentUri(
                        appContext,
                        local.path
                    )

                else -> null
            }

            is PickerBrowserItem.ProviderDocument ->
                item.document.uri.takeUnless {
                    item.document.isDirectory
                }

            is PickerBrowserItem.ProviderRoot -> null
        }
    }

    internal suspend fun createLocalDocument(
        parentPath: String,
        displayName: String
    ): Result<Uri> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val parentUri = DocumentUriMapper.documentUri(
                    appContext,
                    parentPath
                )

                DocumentsContract.createDocument(
                    appContext.contentResolver,
                    parentUri,
                    request.createMimeType,
                    displayName
                ) ?: error(appContext.getString(R.string.file_create_failed))
            }
        }
    }

    internal fun resultGrantFlags(): Int {
        return when (pickerMode) {
            PickerMode.GET_CONTENT ->
                Intent.FLAG_GRANT_READ_URI_PERMISSION

            PickerMode.OPEN_DOCUMENT_TREE ->
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

            else ->
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
    }

    private fun matchesRequestedMimeType(path: String): Boolean {
        val actual = MimeTypes.forFileName(path)
        return requestedMimeMatcher(actual)
    }

    private fun isDirectoryWritable(path: String): Boolean {
        return runCatching {
            val file = File(path).canonicalFile
            file.isDirectory && file.canWrite()
        }.getOrDefault(false)
    }

    private suspend fun isLocalTreeSelectionAllowed(path: String): Boolean {
        if (isSafRestrictedPath(path)) return false

        val storages = storageRepository.getStorages()
        if (
            storages.any {
                samePath(it.path, path)
            }
        ) {
            return false
        }

        return storages.none {
            samePath(
                File(it.path, "Download").absolutePath,
                path
            )
        }
    }

    private suspend fun isSafRestrictedPath(path: String): Boolean {
        val file = runCatching {
            File(path).canonicalFile
        }.getOrNull() ?: return true

        return storageRepository.getStorages().any { storage ->
            val root = runCatching {
                File(storage.path).canonicalFile
            }.getOrNull() ?: return@any false

            isSameOrChild(
                File(root, "Android/data"),
                file
            ) || isSameOrChild(
                File(root, "Android/obb"),
                file
            )
        }
    }

    private suspend fun isManagedPath(path: String): Boolean {
        val file = runCatching {
            File(path).canonicalFile
        }.getOrNull() ?: return false

        return storageRepository.getStorages().any {
            isSameOrChild(
                File(it.path),
                file
            )
        }
    }

    private suspend fun isStorageRoot(path: String): Boolean {
        return storageRepository.getStorages().any {
            samePath(it.path, path)
        }
    }

    private suspend fun findStorageRoot(path: String): String? {
        val file = runCatching {
            File(path).canonicalFile
        }.getOrNull() ?: return null

        return storageRepository.getStorages()
            .mapNotNull {
                val root = File(it.path).canonicalFile

                if (isSameOrChild(root, file)) {
                    root.absolutePath
                } else {
                    null
                }
            }
            .maxByOrNull { it.length }
    }

    private fun isSameOrChild(
        parent: File,
        child: File
    ): Boolean {
        val a = runCatching {
            parent.canonicalFile.path
        }.getOrNull() ?: return false

        val b = runCatching {
            child.canonicalFile.path
        }.getOrNull() ?: return false

        return b == a ||
                b.startsWith(
                    a + File.separator
                )
    }

    private fun samePath(
        first: String,
        second: String
    ): Boolean {
        return runCatching {
            File(first).canonicalFile ==
                    File(second).canonicalFile
        }.getOrDefault(false)
    }

    private suspend fun <T> cancellableResult(
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun <T> cancellableOrNull(
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun isSourceOverview(): Boolean {
        val state = _uiState.value
        return state.displayMode == DisplayMode.BROWSE &&
                state.currentLocalPath == null &&
                state.providerStack.isEmpty()
    }

    internal fun localKey(path: String): String {
        return "local:$path"
    }

    private fun providerRootKey(root: DocumentRootInfo): String {
        return "root:${root.authority}:${root.rootId}"
    }

    internal fun providerDocumentKey(
        document: ProviderDocumentInfo
    ): String {
        return "document:${document.authority}:${document.documentId}"
    }

    internal fun setFocusedKey(key: String?) {
        _uiState.update { it.copy(focusedKey = key) }
    }

    internal fun setFocusTargetKey(key: String?) {
        _uiState.update { it.copy(focusTargetKey = key) }
    }

    internal fun dismissProviderError() {
        _uiState.update { it.copy(providerErrorMessage = null) }
    }

    internal fun dismissProviderInfo() {
        _uiState.update { it.copy(providerInfoMessage = null) }
    }


    internal fun finishWithUris(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            eventChannel.trySend(PickerUiEvent.Finish(uris))
        }
    }

    internal fun cancelPicker() {
        eventChannel.trySend(PickerUiEvent.Cancel)
    }

    internal fun startWatchingStorage() {
        storageRepository.startWatching {
            if (isSourceOverview()) {
                showSourceOverview(_uiState.value.focusedKey)
            }
        }
    }

    internal fun stopWatchingStorage() {
        storageRepository.stopWatching()
    }

    internal fun resumeAfterStoragePermission() {
        pendingLocalPath?.let {
            if (hasAllFilesAccess()) {
                openLocalDirectory(it)
            }
        }
    }

    override fun onCleared() {
        stopObservingProviderDirectory()
        providerQueryJob?.cancel()
        providerRefreshJob?.cancel()
        specialQueryJob?.cancel()
        localQueryJob?.cancel()
        initialLocationJob?.cancel()
        providerNavigationJob?.cancel()
        modelScope.cancel()
        storageRepository.stopWatching()
        eventChannel.close()
        super.onCleared()
    }

    private data class LocalDirectorySnapshot(
        val items: List<PickerBrowserItem>?,
        val title: String,
        val writable: Boolean,
        val treeSelectable: Boolean,
        val gridPosition: TvGridPosition = TvGridPosition()
    )

    companion object {
        private const val MAX_SEARCH_RESULTS = 300
        private const val MAX_RECENT_RESULTS = 128
        private const val MAX_PARALLEL_PROVIDER_QUERIES = 4
        private const val LOCAL_DIRECTORY_SNAPSHOT_LIMIT = 12
        private const val MAX_CACHED_ITEMS_PER_DIRECTORY = 2_000
    }
}
