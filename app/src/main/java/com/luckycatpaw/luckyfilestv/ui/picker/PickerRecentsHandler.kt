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
import com.luckycatpaw.luckyfilestv.ui.picker.model.RecentEntry
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

internal class PickerRecentsHandler(
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
    private var recentsJob: Job? = null

    fun runGlobalRecents(onRecentsStarted: () -> Unit) {
        val request = getRequest()
        if (request.mode != PickerMode.OPEN_DOCUMENT && request.mode != PickerMode.GET_CONTENT) return

        recentsJob?.cancel()
        onRecentsStarted()

        uiState.update { it.copy(
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

        recentsJob = modelScope.launch {
            val settings = getSettings()
            val hasProviderAccess = documentsRepository.hasSystemDocumentAccess()
            
            val localEntriesDeferred = async(Dispatchers.IO) {
                localSearchRepository.loadRecents(settings, request.acceptedMimeTypes).map { recent ->
                    RecentEntry(PickerBrowserItem.Local(recent.item), recent.modified)
                }
            }
            
            val rootsDeferred = if (hasProviderAccess) async {
                documentsRepository.queryRoots(
                    request.acceptedMimeTypes,
                    request.localOnly,
                    requireCreate = false,
                    excludeSelf = request.excludeSelf
                )
            } else null

            val localEntries = localEntriesDeferred.await()
            if (uiState.value.displayMode != DisplayMode.RECENTS) return@launch

            val providerEntries = linkedMapOf<String, List<RecentEntry>>()
            publishRecentResults(localEntries, providerEntries)

            if (!hasProviderAccess || rootsDeferred == null) {
                uiState.update { it.copy(
                    providerLoading = false,
                    providerInfoMessage = if (it.pickerItems.isEmpty()) appContext.getString(R.string.no_recent_files) else null
                )}
                return@launch
            }

            val rootsResult = rootsDeferred.await()
            if (uiState.value.displayMode != DisplayMode.RECENTS) return@launch

            var providerErrors = rootsResult.errors.size
            var loadingTimeouts = 0
            val recentRoots = rootsResult.roots.filter { it.supportsRecents }
            val semaphore = Semaphore(MAX_PARALLEL_PROVIDER_QUERIES)

            coroutineScope {
                recentRoots.map { root ->
                    launch {
                        semaphore.withPermit {
                            val outcome = providerQueryRunner.queryUntilSettled(
                                observedUri = DocumentsContract.buildRecentDocumentsUri(root.authority, root.rootId),
                                query = { signal ->
                                    documentsRepository.queryRecentDocuments(root, request.acceptedMimeTypes, request.openableOnly, signal)
                                }
                            ) { recent ->
                                if (uiState.value.displayMode == DisplayMode.RECENTS) {
                                    providerEntries[providerRootKey(root)] = recent.documents.asSequence()
                                        .filterNot { it.isDirectory }
                                        .map { document -> RecentEntry(PickerBrowserItem.ProviderDocument(document, root), document.lastModified ?: 0L) }
                                        .toList()

                                    publishRecentResults(localEntries, providerEntries)
                                    uiState.update { it.copy(
                                        providerInfoMessage = recent.info ?: if (recent.loading) appContext.getString(R.string.providers_still_loading) else appContext.getString(R.string.recents_loading)
                                    )}
                                }
                            }
                            if (uiState.value.displayMode == DisplayMode.RECENTS) {
                                if (outcome.failure != null || outcome.result?.error != null) providerErrors++
                                if (outcome.loadingTimedOut) loadingTimeouts++
                            }
                        }
                    }
                }.joinAll()
            }

            if (uiState.value.displayMode != DisplayMode.RECENTS) return@launch
            uiState.update { it.copy(providerLoading = false, providerInfoMessage = when {
                providerErrors > 0 -> appContext.resources.getQuantityString(R.plurals.provider_load_errors, providerErrors, providerErrors)
                it.pickerItems.isEmpty() -> appContext.getString(R.string.no_recent_files)
                loadingTimeouts > 0 -> appContext.getString(R.string.providers_still_loading)
                else -> null
            })}
        }
    }

    fun cancelRecents() {
        recentsJob?.cancel()
    }

    private fun publishRecentResults(localEntries: List<RecentEntry>, providerEntries: Map<String, List<RecentEntry>>) {
        uiState.update { state ->
            state.copy(
                pickerItems = (localEntries + providerEntries.values.flatten())
                    .sortedByDescending { it.modified }
                    .distinctBy { it.item.key }
                    .take(128)
                    .map { it.item }
            )
        }
    }

    companion object {
        private const val MAX_PARALLEL_PROVIDER_QUERIES = 4
    }
}
