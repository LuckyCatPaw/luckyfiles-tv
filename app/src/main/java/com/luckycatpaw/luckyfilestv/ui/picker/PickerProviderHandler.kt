package com.luckycatpaw.luckyfilestv.ui.picker

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo
import com.luckycatpaw.luckyfilestv.data.repository.DocumentsProviderRepository
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerRequest
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiState
import com.luckycatpaw.luckyfilestv.ui.picker.model.ProviderLocation
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class PickerProviderHandler(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val uiState: MutableStateFlow<PickerUiState>,
    private val documentsRepository: DocumentsProviderRepository,
    private val providerQueryRunner: ProviderQueryRunner,
    private val getRequest: () -> PickerRequest,
    private val providerDocumentKey: (ProviderDocumentInfo) -> String,
    private val updateUiMetadata: () -> Unit
) {
    private var providerQueryJob: Job? = null
    private var providerRefreshJob: Job? = null
    private var observedProviderUri: Uri? = null
    private var providerObserver: ContentObserver? = null

    private var navigationGeneration = 0

    fun incrementNavigation() {
        navigationGeneration++
        providerQueryJob?.cancel()
    }

    fun openProviderRoot(root: DocumentRootInfo) {
        incrementNavigation()
        val generation = navigationGeneration
        stopObservingProviderDirectory()
        providerQueryJob?.cancel()

        uiState.update {
            it.copy(
                displayMode = DisplayMode.BROWSE,
                currentLocalPath = null,
                providerStack = emptyList(),
                providerLoading = true,
                providerErrorMessage = null,
                providerInfoMessage = null
            )
        }

        providerQueryJob = modelScope.launch {
            updateUiMetadata()
            val result = documentsRepository.queryRootDocument(root)
            val doc = result.getOrNull()
            if (doc != null && generation == navigationGeneration) {
                val loc = ProviderLocation(root, doc, doc.displayName)
                uiState.update { it.copy(providerStack = listOf(loc)) }
                refreshProviderDirectory(loc)
            } else if (generation == navigationGeneration) {
                uiState.update {
                    it.copy(
                        providerLoading = false,
                        providerErrorMessage =
                            result.exceptionOrNull()?.message ?: appContext.getString(R.string.folder_load_failed)
                    )
                }
            }
        }
    }

    fun refreshProviderDirectory(location: ProviderLocation, focusKey: String? = null) {
        val generation = navigationGeneration
        stopObservingProviderDirectory()
        providerQueryJob?.cancel()

        uiState.update { it.copy(providerLoading = true, providerErrorMessage = null, providerInfoMessage = null) }

        providerQueryJob = modelScope.launch {
            updateUiMetadata()
            val request = getRequest()
            val directoriesOnly =
                request.mode == PickerMode.CREATE_DOCUMENT || request.mode == PickerMode.OPEN_DOCUMENT_TREE
            val observedUri = DocumentsContract.buildChildDocumentsUri(
                location.root.authority,
                location.document.documentId
            )

            val outcome = providerQueryRunner.queryUntilSettled(
                observedUri = observedUri,
                query = { signal ->
                    documentsRepository.queryChildren(
                        location.root.authority,
                        location.document.documentId,
                        request.acceptedMimeTypes,
                        directoriesOnly,
                        request.openableOnly,
                        signal
                    )
                }
            ) { result ->
                if (navigationGeneration == generation && isCurrentProviderLocation(location)) {
                    uiState.update { state ->
                        state.copy(
                            pickerItems = result.documents.map {
                                PickerBrowserItem.ProviderDocument(it, location.root)
                            },
                            providerInfoMessage =
                                result.info
                                    ?: if (result.loading) {
                                        appContext.getString(
                                            R.string.providers_still_loading
                                        )
                                    } else {
                                        null
                                    },
                            focusTargetKey = focusKey?.takeIf { target ->
                                result.documents.any {
                                    providerDocumentKey(it) ==
                                        target
                                }
                            }
                        )
                    }
                }
            }

            if (navigationGeneration == generation && isCurrentProviderLocation(location)) {
                uiState.update {
                    it.copy(
                        providerLoading = false,
                        providerErrorMessage =
                            outcome.failure?.message
                                ?: outcome.result?.error?.let { appContext.getString(R.string.folder_load_failed) }
                    )
                }
                observeProviderDirectory(location)
            }
        }
    }

    private fun isCurrentProviderLocation(loc: ProviderLocation): Boolean {
        val stack = uiState.value.providerStack
        return stack.isNotEmpty() &&
            stack.last().root == loc.root &&
            stack.last().document.documentId == loc.document.documentId
    }

    private fun observeProviderDirectory(location: ProviderLocation) {
        stopObservingProviderDirectory()
        val uri = DocumentsContract.buildChildDocumentsUri(location.root.authority, location.document.documentId)
        observedProviderUri = uri
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(self: Boolean) = scheduleProviderRefresh()
            override fun onChange(self: Boolean, uri: Uri?) = scheduleProviderRefresh()
        }
        val registered = runCatching {
            appContext.contentResolver.registerContentObserver(uri, true, observer)
        }.isSuccess

        if (registered) {
            providerObserver = observer
        } else {
            observedProviderUri = null
            providerObserver = null
        }
    }

    private fun scheduleProviderRefresh() {
        providerRefreshJob?.cancel()
        providerRefreshJob = modelScope.launch {
            delay(1000.milliseconds)
            uiState.value.providerStack.lastOrNull()?.let { refreshProviderDirectory(it) }
        }
    }

    fun stopObservingProviderDirectory() {
        providerObserver?.let {
            runCatching { appContext.contentResolver.unregisterContentObserver(it) }
        }
        providerObserver = null
        observedProviderUri = null
    }

    fun cancelProviderJobs() {
        providerQueryJob?.cancel()
        providerRefreshJob?.cancel()
    }
}
