package com.luckycatpaw.luckyfilestv.ui.picker

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerKeys
import com.luckycatpaw.luckyfilestv.ui.picker.model.ProviderLocation
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class PickerProviderHandler(private val context: PickerContext) {
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

        context.uiState.update {
            it.copy(
                displayMode = DisplayMode.BROWSE,
                currentLocalPath = null,
                providerStack = emptyList(),
                providerLoading = true,
                providerErrorMessage = null,
                providerInfoMessage = null
            )
        }

        providerQueryJob = context.modelScope.launch {
            context.onUiMetadataChanged()
            val result = context.documentsRepository.queryRootDocument(root)
            val doc = result.getOrNull()
            if (doc != null && generation == navigationGeneration) {
                val loc = ProviderLocation(root, doc, doc.displayName)
                context.uiState.update { it.copy(providerStack = listOf(loc)) }
                refreshProviderDirectory(loc)
            } else if (generation == navigationGeneration) {
                context.uiState.update {
                    it.copy(
                        providerLoading = false,
                        providerErrorMessage =
                            result.exceptionOrNull()?.message
                                ?: context.appContext.getString(R.string.folder_load_failed)
                    )
                }
            }
        }
    }

    fun refreshProviderDirectory(location: ProviderLocation, focusKey: String? = null) {
        val generation = navigationGeneration
        stopObservingProviderDirectory()
        providerQueryJob?.cancel()

        context.uiState.update {
            it.copy(providerLoading = true, providerErrorMessage = null, providerInfoMessage = null)
        }

        providerQueryJob = context.modelScope.launch {
            context.onUiMetadataChanged()
            val request = context.request
            val directoriesOnly = context.directoriesOnly
            val observedUri = DocumentsContract.buildChildDocumentsUri(
                location.root.authority,
                location.document.documentId
            )

            val outcome = context.providerQueryRunner.queryUntilSettled(
                observedUri = observedUri,
                query = { signal ->
                    context.documentsRepository.queryChildren(
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
                    context.uiState.update { state ->
                        state.copy(
                            pickerItems = result.documents.map {
                                PickerBrowserItem.ProviderDocument(it, location.root)
                            },
                            providerInfoMessage =
                                result.info
                                    ?: if (result.loading) {
                                        context.appContext.getString(
                                            R.string.providers_still_loading
                                        )
                                    } else {
                                        null
                                    },
                            focusTargetKey = focusKey?.takeIf { target ->
                                result.documents.any {
                                    PickerKeys.providerDocument(it) ==
                                        target
                                }
                            }
                        )
                    }
                }
            }

            if (navigationGeneration == generation && isCurrentProviderLocation(location)) {
                context.uiState.update {
                    it.copy(
                        providerLoading = false,
                        providerErrorMessage =
                            outcome.failure?.message
                                ?: outcome.result?.error?.let {
                                    context.appContext.getString(R.string.folder_load_failed)
                                }
                    )
                }
                observeProviderDirectory(location)
            }
        }
    }

    private fun isCurrentProviderLocation(loc: ProviderLocation): Boolean {
        val stack = context.uiState.value.providerStack
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
            context.appContext.contentResolver.registerContentObserver(uri, true, observer)
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
        providerRefreshJob = context.modelScope.launch {
            delay(1000.milliseconds)
            context.uiState.value.providerStack.lastOrNull()?.let { refreshProviderDirectory(it) }
        }
    }

    fun stopObservingProviderDirectory() {
        providerObserver?.let {
            runCatching { context.appContext.contentResolver.unregisterContentObserver(it) }
        }
        providerObserver = null
        observedProviderUri = null
    }

    fun cancelProviderJobs() {
        providerQueryJob?.cancel()
        providerRefreshJob?.cancel()
    }
}
