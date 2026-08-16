package com.luckycatpaw.luckyfilestv.ui.picker

import android.content.Context
import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.repository.DocumentsProviderRepository
import com.luckycatpaw.luckyfilestv.data.repository.LocalFileSearchRepository
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerRequest
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class PickerSearchHandler(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val uiState: MutableStateFlow<PickerUiState>,
    private val documentsRepository: DocumentsProviderRepository,
    private val localSearchRepository: LocalFileSearchRepository,
    private val providerQueryRunner: ProviderQueryRunner,
    private val getRequest: () -> PickerRequest,
    private val getSettings: () -> FileManagerSettings,
    private val providerRootKey: (DocumentRootInfo) -> String
) {
    private var searchJob: Job? = null

    fun runGlobalSearch(query: String, onSearchStarted: () -> Unit) {
        searchJob?.cancel()
        onSearchStarted()

        uiState.update { it.copy(
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

        searchJob = modelScope.launch {
            val request = getRequest()
            val settings = getSettings()
            val directoriesOnly = request.mode == PickerMode.CREATE_DOCUMENT || request.mode == PickerMode.OPEN_DOCUMENT_TREE
            val hasProviderAccess = documentsRepository.hasSystemDocumentAccess()
            
            val localResultsDeferred = async(Dispatchers.IO) {
                localSearchRepository.search(query, directoriesOnly, settings, request.acceptedMimeTypes).map { PickerBrowserItem.Local(it) }
            }
            val rootsDeferred = if (hasProviderAccess) async {
                documentsRepository.queryRoots(
                    request.acceptedMimeTypes,
                    request.localOnly,
                    request.mode == PickerMode.CREATE_DOCUMENT,
                    request.excludeSelf
                )
            } else null

            val localResults = localResultsDeferred.await()
            if (!isCurrentSearch(query)) return@launch

            val providerResults = linkedMapOf<String, List<PickerBrowserItem>>()
            publishSearchResults(query, localResults, providerResults)

            if (!hasProviderAccess || rootsDeferred == null) {
                uiState.update { it.copy(
                    providerLoading = false,
                    providerInfoMessage = if (it.pickerItems.isEmpty()) appContext.getString(R.string.no_search_results, query) else null
                )}
                return@launch
            }

            val rootsResult = rootsDeferred.await()
            if (!isCurrentSearch(query)) return@launch

            var providerErrors = rootsResult.errors.size
            var loadingTimeouts = 0
            val searchableRoots = rootsResult.roots.filter { it.supportsSearch }
            val semaphore = Semaphore(MAX_PARALLEL_PROVIDER_QUERIES)

            coroutineScope {
                searchableRoots.map { root ->
                    launch {
                        semaphore.withPermit {
                            val outcome = providerQueryRunner.queryUntilSettled(
                                observedUri = DocumentsContract.buildSearchDocumentsUri(root.authority, root.rootId, query),
                                query = { signal -> documentsRepository.searchDocuments(root, query, request.acceptedMimeTypes, directoriesOnly, request.openableOnly, signal) }
                            ) { search ->
                                if (isCurrentSearch(query)) {
                                    providerResults[providerRootKey(root)] = search.documents.map { PickerBrowserItem.ProviderDocument(it, root) }
                                    publishSearchResults(query, localResults, providerResults)
                                    uiState.update { it.copy(
                                        providerInfoMessage = search.info ?: if (search.loading) appContext.getString(R.string.providers_still_loading) else appContext.getString(R.string.search_running)
                                    )}
                                }
                            }
                            if (isCurrentSearch(query)) {
                                if (outcome.failure != null || outcome.result?.error != null) providerErrors++
                                if (outcome.loadingTimedOut) loadingTimeouts++
                            }
                        }
                    }
                }.joinAll()
            }

            if (!isCurrentSearch(query)) return@launch
            uiState.update { it.copy(providerLoading = false, providerInfoMessage = when {
                providerErrors > 0 -> appContext.getString(R.string.provider_search_errors, providerErrors)
                it.pickerItems.isEmpty() -> appContext.getString(R.string.no_search_results, query)
                loadingTimeouts > 0 -> appContext.getString(R.string.providers_still_loading)
                else -> null
            })}
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
    }

    private fun isCurrentSearch(query: String): Boolean = uiState.value.displayMode == DisplayMode.SEARCH && uiState.value.currentSearchQuery == query

    private fun publishSearchResults(query: String, localResults: List<PickerBrowserItem>, providerResults: Map<String, List<PickerBrowserItem>>) {
        uiState.update { state ->
            state.copy(
                pickerItems = (localResults + providerResults.values.flatten())
                    .distinctBy { it.key }
                    .map { RankedSearchItem(it, searchRank(it.name, query)) }
                    .sortedWith(compareBy<RankedSearchItem> { it.rank }.thenBy { !it.item.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.item.name })
                    .take(300)
                    .map { it.item }
            )
        }
    }

    private fun searchRank(name: String, query: String): Int = when {
        name.equals(query, ignoreCase = true) -> 0
        name.startsWith(query, ignoreCase = true) -> 1
        else -> 2
    }

    private data class RankedSearchItem(val item: PickerBrowserItem, val rank: Int)

    companion object {
        private const val MAX_PARALLEL_PROVIDER_QUERIES = 4
    }
}
