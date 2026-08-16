package com.luckycatpaw.luckyfilestv.ui.picker

import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerKeys
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_SEARCH_RESULTS = 300

internal class PickerSearchHandler(private val context: PickerContext) {

    private var searchJob: Job? = null

    fun runGlobalSearch(query: String, onSearchStarted: () -> Unit) {
        searchJob?.cancel()
        onSearchStarted()

        val runningMessage = context.getString(R.string.search_running)
        val emptyMessage = context.getString(R.string.no_search_results, query)

        context.beginGlobalQuery(DisplayMode.SEARCH, runningMessage, searchQuery = query)

        searchJob = context.modelScope.launch {
            val request = context.request
            val isCurrent = { isCurrentSearch(query) }

            val localDeferred = async(Dispatchers.IO) {
                context.localSearchRepository
                    .search(query, context.directoriesOnly, context.settings, request.acceptedMimeTypes)
                    .map { PickerBrowserItem.Local(it) }
            }

            val hasProviderAccess = context.documentsRepository.hasSystemDocumentAccess()
            val rootsDeferred = if (hasProviderAccess) {
                async {
                    context.documentsRepository.queryRoots(
                        request.acceptedMimeTypes,
                        request.localOnly,
                        request.mode == PickerMode.CREATE_DOCUMENT,
                        request.excludeSelf
                    )
                }
            } else {
                null
            }

            val localResults = localDeferred.await()
            if (!isCurrent()) return@launch

            val providerResults = linkedMapOf<String, List<PickerBrowserItem>>()
            publish(query, localResults, providerResults)

            if (rootsDeferred == null) {
                context.finishWithoutProviders(emptyMessage)
                return@launch
            }

            val rootsResult = rootsDeferred.await()
            if (!isCurrent()) return@launch

            val stats = context.fanOutAcrossRoots(
                roots = rootsResult.roots.filter { it.supportsSearch },
                initialErrors = rootsResult.errors.size,
                isCurrent = isCurrent,
                observedUri = { root ->
                    DocumentsContract.buildSearchDocumentsUri(root.authority, root.rootId, query)
                },
                query = { root, signal ->
                    context.documentsRepository.searchDocuments(
                        root,
                        query,
                        request.acceptedMimeTypes,
                        context.directoriesOnly,
                        request.openableOnly,
                        signal
                    )
                }
            ) { root, result ->
                providerResults[PickerKeys.providerRoot(root)] =
                    result.documents.map { PickerBrowserItem.ProviderDocument(it, root) }
                publish(query, localResults, providerResults)
                context.uiState.update {
                    it.copy(providerInfoMessage = context.partialResultMessage(result, runningMessage))
                }
            }

            if (!isCurrent()) return@launch
            context.finishGlobalQuery(stats, emptyMessage, R.plurals.provider_search_errors)
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
    }

    private fun isCurrentSearch(query: String): Boolean = context.uiState.value.displayMode == DisplayMode.SEARCH &&
        context.uiState.value.currentSearchQuery == query

    private fun publish(
        query: String,
        localResults: List<PickerBrowserItem>,
        providerResults: Map<String, List<PickerBrowserItem>>
    ) {
        context.uiState.update { state ->
            state.copy(
                pickerItems = (localResults + providerResults.values.flatten())
                    .asSequence()
                    .distinctBy { it.key }
                    .map { RankedSearchItem(it, searchRank(it.name, query)) }
                    .sortedWith(
                        compareBy<RankedSearchItem> { it.rank }
                            .thenBy { !it.item.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.item.name }
                    )
                    .take(MAX_SEARCH_RESULTS)
                    .map { it.item }
                    .toList()
            )
        }
    }

    private fun searchRank(name: String, query: String): Int = when {
        name.equals(query, ignoreCase = true) -> 0
        name.startsWith(query, ignoreCase = true) -> 1
        else -> 2
    }

    private data class RankedSearchItem(val item: PickerBrowserItem, val rank: Int)
}
