package com.luckycatpaw.luckyfilestv.ui.picker

import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerKeys
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.RecentEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_RECENT_RESULTS = 128

internal class PickerRecentsHandler(private val context: PickerContext) {

    private var recentsJob: Job? = null

    fun runGlobalRecents(onRecentsStarted: () -> Unit) {
        val request = context.request
        if (request.mode != PickerMode.OPEN_DOCUMENT && request.mode != PickerMode.GET_CONTENT) return

        recentsJob?.cancel()
        onRecentsStarted()

        val runningMessage = context.getString(R.string.recents_loading)
        val emptyMessage = context.getString(R.string.no_recent_files)

        context.beginGlobalQuery(DisplayMode.RECENTS, runningMessage)

        recentsJob = context.modelScope.launch {
            val isCurrent = { context.uiState.value.displayMode == DisplayMode.RECENTS }

            val localDeferred = async(Dispatchers.IO) {
                context.localSearchRepository
                    .loadRecents(context.settings, request.acceptedMimeTypes)
                    .map { RecentEntry(PickerBrowserItem.Local(it.item), it.modified) }
            }

            val hasProviderAccess = context.documentsRepository.hasSystemDocumentAccess()
            val rootsDeferred = if (hasProviderAccess) {
                async {
                    context.documentsRepository.queryRoots(
                        request.acceptedMimeTypes,
                        request.localOnly,
                        requireCreate = false,
                        excludeSelf = request.excludeSelf
                    )
                }
            } else {
                null
            }

            val localEntries = localDeferred.await()
            if (!isCurrent()) return@launch

            val providerEntries = linkedMapOf<String, List<RecentEntry>>()
            publish(localEntries, providerEntries)

            if (rootsDeferred == null) {
                context.finishWithoutProviders(emptyMessage)
                return@launch
            }

            val rootsResult = rootsDeferred.await()
            if (!isCurrent()) return@launch

            val stats = context.fanOutAcrossRoots(
                roots = rootsResult.roots.filter { it.supportsRecents },
                initialErrors = rootsResult.errors.size,
                isCurrent = isCurrent,
                observedUri = { root ->
                    DocumentsContract.buildRecentDocumentsUri(root.authority, root.rootId)
                },
                query = { root, signal ->
                    context.documentsRepository.queryRecentDocuments(
                        root,
                        request.acceptedMimeTypes,
                        request.openableOnly,
                        signal
                    )
                }
            ) { root, result ->
                providerEntries[PickerKeys.providerRoot(root)] = result.documents
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .map { document ->
                        RecentEntry(
                            PickerBrowserItem.ProviderDocument(document, root),
                            document.lastModified ?: 0L
                        )
                    }
                    .toList()

                publish(localEntries, providerEntries)
                context.uiState.update {
                    it.copy(providerInfoMessage = context.partialResultMessage(result, runningMessage))
                }
            }

            if (!isCurrent()) return@launch
            context.finishGlobalQuery(stats, emptyMessage, R.plurals.provider_load_errors)
        }
    }

    fun cancelRecents() {
        recentsJob?.cancel()
    }

    private fun publish(localEntries: List<RecentEntry>, providerEntries: Map<String, List<RecentEntry>>) {
        context.uiState.update { state ->
            state.copy(
                pickerItems = (localEntries + providerEntries.values.flatten())
                    .sortedByDescending { it.modified }
                    .distinctBy { it.item.key }
                    .take(MAX_RECENT_RESULTS)
                    .map { it.item }
            )
        }
    }
}
