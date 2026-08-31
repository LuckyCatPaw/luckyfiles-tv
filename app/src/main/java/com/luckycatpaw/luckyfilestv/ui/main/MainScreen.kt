package com.luckycatpaw.luckyfilestv.ui.main

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.ui.browser.BrowserScreen
import com.luckycatpaw.luckyfilestv.ui.browser.ItemActionMenuOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.NameInputOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.OperationProgressOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.PropertiesOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.TransferConflictOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.TransferProgressOverlay
import com.luckycatpaw.luckyfilestv.ui.common.ConfirmOverlay
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferConflictAnswer
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferMode
import com.luckycatpaw.luckyfilestv.ui.settings.SettingsOverlay
import com.luckycatpaw.luckyfilestv.ui.theme.LuckyFilesTheme
import com.luckycatpaw.luckyfilestv.util.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    val propertiesReadFailedText = stringResource(R.string.properties_read_failed)
    val noAppToOpenText = stringResource(R.string.no_app_to_open)
    val noItemsSelectedText = stringResource(R.string.no_items_selected)

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var focusRestoreKey by rememberSaveable { mutableIntStateOf(0) }

    var actionMenuItem by remember { mutableStateOf<BrowserItem?>(null) }
    var renameItem by remember { mutableStateOf<BrowserItem?>(null) }
    var deleteItem by remember { mutableStateOf<BrowserItem?>(null) }
    var multiDeleteItems by remember { mutableStateOf<List<BrowserItem>?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    var propertiesItem by remember { mutableStateOf<BrowserItem?>(null) }
    var propertiesData by remember { mutableStateOf<FileProperties?>(null) }
    var propertiesError by remember { mutableStateOf<String?>(null) }
    var propertiesJob by remember { mutableStateOf<Job?>(null) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    var operationMessage by remember { mutableStateOf<String?>(null) }

    fun restoreBrowserFocus(path: String? = viewModel.focusedPath) {
        viewModel.setFocusTargetPath(path)
        focusRestoreKey++
    }

    fun clearSelection() {
        selectionMode = false
        selectedPaths = emptySet()
    }

    fun cancelSelection() {
        clearSelection()
        restoreBrowserFocus()
    }

    fun toggleSelection(item: BrowserItem) {
        if (item is BrowserItem.Storage) return
        selectedPaths = if (item.path in selectedPaths) {
            selectedPaths - item.path
        } else {
            selectedPaths + item.path
        }
    }

    fun selectedItems(): List<BrowserItem> = uiState.browserItems.filter {
        it.path in selectedPaths && it !is BrowserItem.Storage
    }

    fun openProperties(item: BrowserItem) {
        propertiesJob?.cancel()
        propertiesItem = item
        propertiesData = null
        propertiesError = null

        propertiesJob = scope.launch {
            val result = withContext(Dispatchers.IO) { viewModel.getProperties(item.path) }
            if (propertiesItem?.path != item.path) return@launch

            result
                .onSuccess {
                    propertiesData = it
                    propertiesError = null
                }
                .onFailure {
                    propertiesData = null
                    propertiesError = it.message ?: propertiesReadFailedText
                }
        }
    }

    val operationCancelledPartialText = stringResource(R.string.operation_cancelled_partial)
    val transferCompletion = uiState.transferCompletion
    val transferSummaryText = if (transferCompletion != null) {
        val result = transferCompletion.result
        val completedCount = result.completedPaths.size
        pluralStringResource(
            R.plurals.transfer_summary,
            completedCount,
            completedCount,
            result.skippedCount,
            result.issues.size
        )
    } else ""

    val transferMultipleWarningsText = stringResource(R.string.transfer_multiple_warnings)
    val transferSourceWarningCountText = if (transferCompletion != null) {
        val count = transferCompletion.result.sourceDeleteWarningCount
        pluralStringResource(R.plurals.transfer_source_warning_count, count, count)
    } else ""
    val transferBackupWarningCountText = if (transferCompletion != null) {
        val count = transferCompletion.result.cleanupWarningCount
        pluralStringResource(R.plurals.transfer_backup_warning_count, count, count)
    } else ""

    val moveSourceDeleteWarningText = if (transferCompletion != null) {
        val count = transferCompletion.result.sourceDeleteWarningCount
        pluralStringResource(R.plurals.move_source_delete_warning, count, count)
    } else ""
    val transferCleanupWarningText = if (transferCompletion != null) {
        val count = transferCompletion.result.cleanupWarningCount
        pluralStringResource(R.plurals.transfer_cleanup_warning, count, count)
    } else ""

    val itemsCopiedText = if (transferCompletion != null) {
        val count = transferCompletion.result.completedPaths.size
        pluralStringResource(R.plurals.items_copied, count, count)
    } else ""
    val itemsMovedText = if (transferCompletion != null) {
        val count = transferCompletion.result.completedPaths.size
        pluralStringResource(R.plurals.items_moved, count, count)
    } else ""

    LaunchedEffect(uiState.transferCompletion) {
        val completion = uiState.transferCompletion ?: return@LaunchedEffect
        restoreBrowserFocus(completion.focusPath)

        val result = completion.result
        val message = when {
            result.cancelled ->
                operationCancelledPartialText

            result.issues.isNotEmpty() || result.skippedCount > 0 ->
                transferSummaryText

            result.sourceDeleteWarningCount > 0 && result.cleanupWarningCount > 0 ->
                transferMultipleWarningsText.format(
                    transferSourceWarningCountText,
                    transferBackupWarningCountText
                )

            result.sourceDeleteWarningCount > 0 ->
                moveSourceDeleteWarningText

            result.cleanupWarningCount > 0 ->
                transferCleanupWarningText

            completion.operation == TransferMode.COPY ->
                itemsCopiedText

            else ->
                itemsMovedText
        }

        val needsAttention = result.cancelled ||
            result.issues.isNotEmpty() ||
            result.cleanupWarningCount > 0 ||
            result.sourceDeleteWarningCount > 0

        Toast
            .makeText(
                context,
                message,
                if (needsAttention) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            )
            .show()

        viewModel.consumeTransferCompletion()
    }

    LuckyFilesTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val overlayOpen = showSettings ||
                    actionMenuItem != null ||
                    renameItem != null ||
                    deleteItem != null ||
                    multiDeleteItems != null ||
                    showNewFolderDialog ||
                    propertiesItem != null ||
                    operationMessage != null ||
                    uiState.transferProgress != null ||
                    uiState.conflictRequest != null

                val transferCount = uiState.transferSources.size
                val transferActionLabel = when (uiState.transferMode) {
                    TransferMode.COPY -> if (transferCount > 1) {
                        stringResource(R.string.copy_here_count, transferCount)
                    } else {
                        stringResource(R.string.copy_here)
                    }

                    TransferMode.MOVE -> if (transferCount > 1) {
                        stringResource(R.string.move_here_count, transferCount)
                    } else {
                        stringResource(R.string.move_here)
                    }

                    null -> null
                }

                val displayedPath = uiState.currentPath

                BrowserScreen(
                    items = uiState.browserItems,
                    title = uiState.title,
                    optimizeFileNames = uiState.settings.optimizeFileNames,
                    useFolderJpgAsIcon = uiState.settings.useFolderJpgAsIcon,
                    focusPath = uiState.focusTargetPath,
                    focusRequestKey = focusRestoreKey,
                    gridStateKey = displayedPath,
                    initialGridPosition = viewModel.directoryGridPosition(displayedPath),
                    onGridPositionChanged = { viewModel.saveDirectoryGridPosition(displayedPath, it) },
                    focusEnabled = !overlayOpen,
                    canCreateFolder = displayedPath != null && uiState.transferMode == null && !selectionMode,
                    transferActionLabel = if (displayedPath != null) transferActionLabel else null,
                    selectionMode = selectionMode,
                    selectedPaths = selectedPaths,
                    onItemFocused = { viewModel.setFocusedPath(it.path) },
                    onItemClick = onItemClick@{ item ->
                        if (overlayOpen) return@onItemClick

                        if (uiState.transferMode != null) {
                            if (item is BrowserItem.Folder || item is BrowserItem.Storage) {
                                viewModel.openItem(item)
                            }
                            return@onItemClick
                        }

                        if (selectionMode) toggleSelection(item) else viewModel.openItem(item)

                        if (!selectionMode && item is BrowserItem.File) {
                            if (!FileOpener.open(context, item.path)) {
                                Toast
                                    .makeText(
                                        context,
                                        noAppToOpenText,
                                        Toast.LENGTH_SHORT
                                    )
                                    .show()
                            }
                        }
                    },
                    onItemLongClick = onItemLongClick@{ item ->
                        if (overlayOpen || uiState.transferMode != null || item is BrowserItem.Storage) {
                            return@onItemLongClick
                        }
                        if (selectionMode) toggleSelection(item) else actionMenuItem = item
                    },
                    onCreateFolderClick = {
                        if (!overlayOpen && displayedPath != null && !selectionMode) {
                            showNewFolderDialog = true
                        }
                    },
                    onTransferHereClick = { displayedPath?.let { viewModel.startPreparedTransfer(it) } },
                    onTransferCancelClick = {
                        viewModel.cancelPreparedTransfer()
                        restoreBrowserFocus()
                    },
                    onSelectAllClick = {
                        selectedPaths = uiState.browserItems
                            .filterNot { it is BrowserItem.Storage }
                            .map { it.path }
                            .toSet()
                    },
                    onSelectionCopyClick = {
                        viewModel.startTransfer(TransferMode.COPY, selectedItems()) {
                            cancelSelection()
                        }
                    },
                    onSelectionMoveClick = {
                        viewModel.startTransfer(TransferMode.MOVE, selectedItems()) {
                            cancelSelection()
                        }
                    },
                    onSelectionDeleteClick = {
                        val items = selectedItems()
                        if (items.isEmpty()) {
                            Toast
                                .makeText(
                                    context,
                                    noItemsSelectedText,
                                    Toast.LENGTH_SHORT
                                )
                                .show()
                        } else {
                            multiDeleteItems = items
                        }
                    },
                    onSelectionCancelClick = { cancelSelection() },
                    onSettingsClick = { if (!overlayOpen && !selectionMode) showSettings = true }
                )

                actionMenuItem?.let { item ->
                    ItemActionMenuOverlay(
                        item = item,
                        onSelect = {
                            actionMenuItem = null
                            selectionMode = true
                            selectedPaths = setOf(item.path)
                            restoreBrowserFocus(item.path)
                        },
                        onRename = {
                            actionMenuItem = null
                            renameItem = item
                        },
                        onCopy = {
                            actionMenuItem = null
                            viewModel.startTransfer(TransferMode.COPY, listOf(item)) {
                                restoreBrowserFocus(item.path)
                            }
                        },
                        onMove = {
                            actionMenuItem = null
                            viewModel.startTransfer(TransferMode.MOVE, listOf(item)) {
                                restoreBrowserFocus(item.path)
                            }
                        },
                        onDelete = {
                            actionMenuItem = null
                            deleteItem = item
                        },
                        onProperties = {
                            actionMenuItem = null
                            openProperties(item)
                        },
                        onDismiss = {
                            actionMenuItem = null
                            restoreBrowserFocus(item.path)
                        }
                    )
                }

                propertiesItem?.let { item ->
                    PropertiesOverlay(
                        itemName = item.name,
                        properties = propertiesData,
                        error = propertiesError,
                        onDismiss = {
                            propertiesJob?.cancel()
                            propertiesJob = null
                            propertiesItem = null
                            propertiesData = null
                            propertiesError = null
                            restoreBrowserFocus(item.path)
                        }
                    )
                }

                renameItem?.let { item ->
                    NameInputOverlay(
                        title = stringResource(R.string.rename),
                        initialValue = item.name,
                        confirmLabel = stringResource(R.string.save),
                        onConfirm = { newName ->
                            viewModel.renameItem(item, newName) {
                                renameItem = null
                                restoreBrowserFocus(newName)
                            }
                        },
                        onDismiss = {
                            renameItem = null
                            restoreBrowserFocus(item.path)
                        }
                    )
                }

                deleteItem?.let { item ->
                    val deletingItemText = stringResource(R.string.deleting_item, item.name)
                    ConfirmOverlay(
                        title = stringResource(R.string.confirm_delete),
                        message = item.name,
                        confirmLabel = stringResource(R.string.delete),
                        focusKey = item.path,
                        onConfirm = {
                            deleteItem = null
                            operationMessage = deletingItemText

                            viewModel.deleteItems(listOf(item)) { successCount, failureCount ->
                                operationMessage = null
                                restoreBrowserFocus()
                                showDeleteResult(context, successCount, failureCount)
                            }
                        },
                        onDismiss = {
                            deleteItem = null
                            restoreBrowserFocus(item.path)
                        }
                    )
                }

                multiDeleteItems?.let { items ->
                    val deletingItemProgressPattern = stringResource(R.string.deleting_item_progress)
                    ConfirmOverlay(
                        title = pluralStringResource(R.plurals.confirm_delete_count, items.size, items.size),
                        message = stringResource(R.string.confirm_delete_selected_description),
                        confirmLabel = stringResource(R.string.delete),
                        focusKey = items.size,
                        onConfirm = {
                            multiDeleteItems = null
                            clearSelection()

                            viewModel.deleteItems(
                                items = items,
                                onProgress = { index, total, item ->
                                    operationMessage = deletingItemProgressPattern.format(
                                        index,
                                        total,
                                        item.name
                                    )
                                },
                                onFinished = { successCount, failureCount ->
                                    operationMessage = null
                                    restoreBrowserFocus()
                                    showDeleteResult(context, successCount, failureCount, multiple = true)
                                }
                            )
                        },
                        onDismiss = {
                            multiDeleteItems = null
                            restoreBrowserFocus()
                        }
                    )
                }

                if (showNewFolderDialog) {
                    NameInputOverlay(
                        title = stringResource(R.string.new_folder),
                        initialValue = "",
                        confirmLabel = stringResource(R.string.create),
                        onConfirm = { name ->
                            displayedPath?.let { parentPath ->
                                viewModel.createFolderIn(parentPath, name) { createdPath ->
                                    showNewFolderDialog = false
                                    restoreBrowserFocus(createdPath)
                                }
                            }
                        },
                        onDismiss = {
                            showNewFolderDialog = false
                            restoreBrowserFocus()
                        }
                    )
                }

                if (showSettings) {
                    SettingsOverlay(
                        settings = uiState.settings,
                        onLanguageTagChanged = { scope.launch { viewModel.setLanguageTag(it) } },
                        onHideFolderJpgChanged = { scope.launch { viewModel.setHideFolderJpg(it) } },
                        onUseFolderJpgAsIconChanged = { scope.launch { viewModel.setUseFolderJpgAsIcon(it) } },
                        onOptimizeFileNamesChanged = { scope.launch { viewModel.setOptimizeFileNames(it) } },
                        onSortModeChanged = { scope.launch { viewModel.setSortMode(it) } },
                        onSortAscendingChanged = { scope.launch { viewModel.setSortAscending(it) } },
                        onFoldersFirstChanged = { scope.launch { viewModel.setFoldersFirst(it) } },
                        onDismiss = {
                            showSettings = false
                            restoreBrowserFocus()
                        }
                    )
                }

                uiState.conflictRequest?.let { request ->
                    TransferConflictOverlay(
                        sourceName = request.sourceName,
                        targetDirectory = request.targetDirectory,
                        multipleItems = request.multipleItems,
                        onDecision = { policy, applyToAll ->
                            viewModel.answerTransferConflict(
                                TransferConflictAnswer(
                                    policy = policy,
                                    applyToAll = applyToAll,
                                    cancelled = false
                                )
                            )
                        },
                        onCancel = {
                            viewModel.answerTransferConflict(
                                TransferConflictAnswer(
                                    policy = null,
                                    applyToAll = false,
                                    cancelled = true
                                )
                            )
                        }
                    )
                }

                uiState.transferProgress?.let { transfer ->
                    val details = transfer.details

                    TransferProgressOverlay(
                        title = transfer.title,
                        currentItem = details.currentItem,
                        totalItems = details.totalItems,
                        currentName = details.currentName,
                        bytesProcessed = details.bytesProcessed,
                        totalBytes = details.totalBytes,
                        bytesPerSecond = details.bytesPerSecond,
                        onCancel = viewModel::cancelRunningTransfer
                    )
                }

                operationMessage?.let { OperationProgressOverlay(message = it) }

                BackHandler(enabled = displayedPath != null && !overlayOpen && !selectionMode) {
                    viewModel.navigateBack()
                }
                BackHandler(enabled = displayedPath == null && uiState.transferMode != null && !overlayOpen) {
                    viewModel.cancelPreparedTransfer()
                    restoreBrowserFocus()
                }
                BackHandler(enabled = selectionMode && !overlayOpen) {
                    cancelSelection()
                }
            }
        }
    }
}

private fun showDeleteResult(context: Context, successCount: Int, failureCount: Int, multiple: Boolean = false) {
    val resources = context.resources
    val message = when {
        failureCount > 0 -> resources.getQuantityString(
            R.plurals.delete_summary,
            successCount,
            successCount,
            failureCount
        )

        multiple -> resources.getQuantityString(
            R.plurals.items_deleted,
            successCount,
            successCount
        )

        else -> context.getString(R.string.deleted)
    }

    Toast
        .makeText(
            context,
            message,
            if (failureCount > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        )
        .show()
}
