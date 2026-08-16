package com.luckycatpaw.luckyfilestv.ui.picker

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.ui.browser.NameInputOverlay
import com.luckycatpaw.luckyfilestv.ui.picker.model.DisplayMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import com.luckycatpaw.luckyfilestv.ui.theme.LuckyFilesTheme
import kotlinx.coroutines.launch

@Composable
internal fun DocumentPickerScreen(viewModel: DocumentPickerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var selectedItems by remember {
        mutableStateOf<Map<String, PickerBrowserItem>>(emptyMap())
    }
    var focusRestoreKey by remember { mutableIntStateOf(0) }

    fun restoreFocus(key: String? = uiState.focusedKey) {
        viewModel.setFocusTargetKey(key)
        focusRestoreKey++
    }

    fun toggleSelection(item: PickerBrowserItem) {
        if (!item.isFile) return

        selectedItems = if (item.key in selectedItems) {
            selectedItems - item.key
        } else {
            selectedItems + (item.key to item)
        }
    }

    fun finishMultipleSelection() {
        val uris = selectedItems.values
            .filter { it.isFile }
            .mapNotNull(viewModel::uriForPickerItem)

        if (uris.isEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.no_files_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        viewModel.finishWithUris(uris)
    }

    LuckyFilesTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                val browsing = uiState.displayMode == DisplayMode.BROWSE

                val primaryActionLabel = when {
                    viewModel.request.allowMultiple ->
                        context.getString(R.string.select_count, selectedItems.size)

                    (
                        browsing &&
                            (viewModel.pickerMode == PickerMode.CREATE_DOCUMENT) &&
                            uiState.currentLocalDirectoryWritable
                        ) ->
                        context.getString(R.string.save_here)

                    browsing &&
                        viewModel.pickerMode == PickerMode.OPEN_DOCUMENT_TREE &&
                        uiState.currentLocalTreeSelectable ->
                        context.getString(R.string.select_this_folder)

                    else -> uiState.primaryActionLabel
                }

                val displayedLocalPath = uiState.currentLocalPath

                PickerBrowserScreen(
                    items = uiState.pickerItems,
                    title = uiState.title,
                    optimizeFileNames = uiState.settings.optimizeFileNames,
                    useFolderJpgAsIcon = uiState.settings.useFolderJpgAsIcon,
                    focusKey = uiState.focusTargetKey,
                    focusRequestKey = focusRestoreKey,
                    gridStateKey = when {
                        displayedLocalPath != null ->
                            "local:$displayedLocalPath"

                        uiState.providerStack.isNotEmpty() ->
                            uiState.providerStack.last().document.uri

                        else -> uiState.displayMode
                    },
                    initialGridPosition =
                        viewModel.localGridPosition(displayedLocalPath),
                    onGridPositionChanged = { position ->
                        viewModel.saveLocalGridPosition(
                            displayedLocalPath,
                            position
                        )
                    },
                    selectedKeys = selectedItems.keys,
                    canCreateFolder = uiState.canCreateFolder,
                    primaryActionLabel = primaryActionLabel,
                    showSearchAction = true,
                    showRecentsAction =
                        viewModel.pickerMode == PickerMode.OPEN_DOCUMENT ||
                            viewModel.pickerMode == PickerMode.GET_CONTENT,
                    showCancelAction = true,
                    onSearchClick = {
                        showSearchDialog = true
                    },
                    onRecentsClick = {
                        viewModel.runGlobalRecents()
                    },
                    onItemClick = itemClick@{ item ->
                        if (uiState.providerErrorMessage != null) {
                            return@itemClick
                        }

                        when (item) {
                            is PickerBrowserItem.Local -> {
                                when (val local = item.item) {
                                    is BrowserItem.Storage ->
                                        viewModel.openLocalDirectory(local.path)

                                    is BrowserItem.Folder ->
                                        viewModel.openLocalDirectory(local.path)

                                    is BrowserItem.File -> {
                                        when (viewModel.pickerMode) {
                                            PickerMode.OPEN_DOCUMENT,
                                            PickerMode.GET_CONTENT -> {
                                                if (viewModel.request.allowMultiple) {
                                                    toggleSelection(item)
                                                } else {
                                                    viewModel.uriForPickerItem(item)?.let {
                                                        viewModel.finishWithUris(listOf(it))
                                                    }
                                                }
                                            }

                                            else -> Unit
                                        }
                                    }
                                }
                            }

                            is PickerBrowserItem.ProviderRoot ->
                                viewModel.openProviderRoot(item.root)

                            is PickerBrowserItem.ProviderDocument -> {
                                if (item.document.isDirectory) {
                                    viewModel.openProviderResultDirectory(item)
                                } else {
                                    when (viewModel.pickerMode) {
                                        PickerMode.OPEN_DOCUMENT,
                                        PickerMode.GET_CONTENT -> {
                                            if (viewModel.request.allowMultiple) {
                                                toggleSelection(item)
                                            } else {
                                                viewModel.finishWithUris(
                                                    listOf(item.document.uri)
                                                )
                                            }
                                        }

                                        else -> Unit
                                    }
                                }
                            }
                        }
                    },
                    onItemLongClick = { item ->
                        if (
                            uiState.providerErrorMessage == null &&
                            viewModel.request.allowMultiple &&
                            item.isFile
                        ) {
                            toggleSelection(item)
                        }
                    },
                    onItemFocused = { item ->
                        viewModel.setFocusedKey(item.key)
                    },
                    onCreateFolderClick = {
                        if (uiState.canCreateFolder) {
                            showCreateFolderDialog = true
                        }
                    },
                    onPrimaryActionClick = primary@{
                        when {
                            viewModel.request.allowMultiple -> {
                                finishMultipleSelection()
                            }

                            viewModel.pickerMode == PickerMode.OPEN_DOCUMENT_TREE -> {
                                scope.launch {
                                    val uri = viewModel.currentTreeUri()
                                        ?: return@launch

                                    viewModel.finishWithUris(listOf(uri))
                                }
                            }

                            viewModel.pickerMode == PickerMode.CREATE_DOCUMENT -> {
                                scope.launch {
                                    if (viewModel.canCreateInCurrentLocation()) {
                                        showCreateFileDialog = true
                                    }
                                }
                            }
                        }
                    },
                    onCancelClick = {
                        viewModel.cancelPicker()
                    }
                )

                if (showSearchDialog) {
                    NameInputOverlay(
                        title = context.getString(R.string.search),
                        initialValue = uiState.currentSearchQuery,
                        confirmLabel = context.getString(R.string.search),
                        onConfirm = search@{ query ->
                            val cleanQuery = query.trim()

                            if (cleanQuery.isBlank()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.search_empty),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@search
                            }

                            showSearchDialog = false
                            viewModel.runGlobalSearch(cleanQuery)
                        },
                        onDismiss = {
                            showSearchDialog = false
                            restoreFocus()
                        }
                    )
                }

                if (showCreateFolderDialog) {
                    NameInputOverlay(
                        title = context.getString(R.string.new_folder),
                        initialValue = "",
                        confirmLabel = context.getString(R.string.create),
                        onConfirm = createFolder@{ folderName ->
                            val cleanName = folderName.trim()
                            if (cleanName.isBlank()) return@createFolder

                            when {
                                uiState.currentLocalPath != null -> {
                                    val parent =
                                        uiState.currentLocalPath ?: return@createFolder

                                    scope.launch {
                                        val result = viewModel.createLocalFolder(
                                            parent,
                                            cleanName
                                        )

                                        result.onSuccess { newPath ->
                                            showCreateFolderDialog = false
                                            viewModel.openLocalDirectory(
                                                parent,
                                                viewModel.localKey(newPath)
                                            )
                                            focusRestoreKey++
                                        }.onFailure {
                                            Toast.makeText(
                                                context,
                                                it.message ?: context.getString(R.string.error_generic),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }

                                uiState.providerStack.isNotEmpty() -> {
                                    val location = uiState.providerStack.last()

                                    scope.launch {
                                        val result = viewModel.createProviderDirectory(
                                            location,
                                            cleanName
                                        )

                                        result.onSuccess { created ->
                                            showCreateFolderDialog = false
                                            viewModel.refreshProviderDirectory(
                                                location,
                                                viewModel.providerDocumentKey(created)
                                            )
                                            focusRestoreKey++
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                error.message
                                                    ?: context.getString(R.string.error_generic),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        },
                        onDismiss = {
                            showCreateFolderDialog = false
                            restoreFocus()
                        }
                    )
                }

                if (showCreateFileDialog) {
                    NameInputOverlay(
                        title = context.getString(R.string.save_file),
                        initialValue = viewModel.request.suggestedFileName,
                        confirmLabel = context.getString(R.string.save),
                        onConfirm = createFile@{ name ->
                            val cleanName = name.trim()
                            if (cleanName.isBlank()) return@createFile

                            when {
                                uiState.currentLocalPath != null -> {
                                    val parent =
                                        uiState.currentLocalPath ?: return@createFile

                                    scope.launch {
                                        viewModel.createLocalDocument(
                                            parent,
                                            cleanName
                                        ).onSuccess {
                                            showCreateFileDialog = false
                                            viewModel.finishWithUris(listOf(it))
                                        }.onFailure {
                                            Toast.makeText(
                                                context,
                                                it.message ?: context.getString(R.string.error_generic),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }

                                uiState.providerStack.isNotEmpty() -> {
                                    val location = uiState.providerStack.last()

                                    scope.launch {
                                        val result = viewModel.createProviderDocument(
                                            location,
                                            cleanName
                                        )

                                        result.onSuccess {
                                            showCreateFileDialog = false
                                            viewModel.finishWithUris(
                                                listOf(it.uri)
                                            )
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                error.message
                                                    ?: context.getString(R.string.error_generic),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        },
                        onDismiss = {
                            showCreateFileDialog = false
                            restoreFocus()
                        }
                    )
                }

                BackHandler(
                    enabled =
                        !showSearchDialog &&
                            !showCreateFileDialog &&
                            !showCreateFolderDialog &&
                            uiState.providerErrorMessage == null
                ) {
                    if (uiState.displayMode != DisplayMode.BROWSE) {
                        viewModel.restoreBrowseSnapshot()
                    } else {
                        viewModel.navigateBack()
                    }
                }

                ProviderStatusOverlay(
                    loading = uiState.providerLoading,
                    info = uiState.providerInfoMessage,
                    error = uiState.providerErrorMessage,
                    onRetry = {
                        when (uiState.displayMode) {
                            DisplayMode.SEARCH ->
                                viewModel.runGlobalSearch(uiState.currentSearchQuery)

                            DisplayMode.RECENTS ->
                                viewModel.runGlobalRecents()

                            DisplayMode.BROWSE ->
                                viewModel.retryCurrentProviderLocation()
                        }
                    },
                    onDismissError = {
                        viewModel.dismissProviderError()
                    },
                    onDismissInfo = {
                        if (!uiState.providerLoading) {
                            viewModel.dismissProviderInfo()
                        }
                    }
                )
            }
        }
    }
}
