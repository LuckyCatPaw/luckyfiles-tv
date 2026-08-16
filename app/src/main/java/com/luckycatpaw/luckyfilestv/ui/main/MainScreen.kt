package com.luckycatpaw.luckyfilestv.ui.main

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferConflictAnswer
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferMode
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.ui.browser.BrowserScreen
import com.luckycatpaw.luckyfilestv.ui.browser.DeleteConfirmOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.ItemActionMenuOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.MultiDeleteConfirmOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.NameInputOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.OperationProgressOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.PropertiesOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.TransferConflictOverlay
import com.luckycatpaw.luckyfilestv.ui.browser.TransferProgressOverlay
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

    fun restoreBrowserFocus(path: String? = uiState.focusedPath) {
        viewModel.setFocusTargetPath(path)
        focusRestoreKey++
    }

    fun openProperties(item: BrowserItem) {
        propertiesJob?.cancel()
        propertiesItem = item
        propertiesData = null
        propertiesError = null

        propertiesJob = scope.launch {
            val result = withContext(Dispatchers.IO) { viewModel.getProperties(item.path) }
            if (propertiesItem?.path != item.path) return@launch

            result.onSuccess {
                propertiesData = it
                propertiesError = null
            }.onFailure {
                propertiesData = null
                propertiesError = it.message ?: context.getString(R.string.properties_read_failed)
            }
        }
    }

    val actionHandler = remember(viewModel, scope) {
        BrowserActionHandler(
            appContext = context,
            modelScope = scope,
            viewModel = viewModel
        )
    }

    fun toggleSelection(item: BrowserItem) {
        if (item is BrowserItem.Storage) return
        selectedPaths = if (item.path in selectedPaths) selectedPaths - item.path else selectedPaths + item.path
    }

    fun cancelSelection() {
        selectionMode = false
        selectedPaths = emptySet()
        restoreBrowserFocus()
    }

    fun selectedItems(): List<BrowserItem> = uiState.browserItems.filter { (it.path in selectedPaths) && (it !is BrowserItem.Storage) }

    LaunchedEffect(uiState.transferCompletion) {
        val completion = uiState.transferCompletion ?: return@LaunchedEffect
        restoreBrowserFocus(completion.focusPath)

        val transferResult = completion.result
        val message = when {
            transferResult.cancelled -> context.getString(R.string.operation_cancelled_partial)
            transferResult.issues.isNotEmpty() || transferResult.skippedCount > 0 ->
                context.resources.getQuantityString(
                    R.plurals.transfer_summary,
                    transferResult.completedPaths.size,
                    transferResult.completedPaths.size,
                    transferResult.skippedCount,
                    transferResult.issues.size
                )
            transferResult.sourceDeleteWarningCount > 0 && transferResult.cleanupWarningCount > 0 -> {
                val sourceCount = transferResult.sourceDeleteWarningCount
                val backupCount = transferResult.cleanupWarningCount
                val sourceMessage = context.resources.getQuantityString(R.plurals.transfer_source_warning_count, sourceCount, sourceCount)
                val backupMessage = context.resources.getQuantityString(R.plurals.transfer_backup_warning_count, backupCount, backupCount)
                context.getString(R.string.transfer_multiple_warnings, sourceMessage, backupMessage)
            }
            transferResult.sourceDeleteWarningCount > 0 -> context.resources.getQuantityString(
                R.plurals.move_source_delete_warning,
                transferResult.sourceDeleteWarningCount,
                transferResult.sourceDeleteWarningCount
            )
            transferResult.cleanupWarningCount > 0 -> context.resources.getQuantityString(
                R.plurals.transfer_cleanup_warning,
                transferResult.cleanupWarningCount,
                transferResult.cleanupWarningCount
            )
            completion.operation == TransferMode.COPY -> context.resources.getQuantityString(
                R.plurals.items_copied,
                transferResult.completedPaths.size,
                transferResult.completedPaths.size
            )
            else -> context.resources.getQuantityString(
                R.plurals.items_moved,
                transferResult.completedPaths.size,
                transferResult.completedPaths.size
            )
        }

        Toast.makeText(context, message, if (transferResult.cancelled || transferResult.issues.isNotEmpty() || transferResult.cleanupWarningCount > 0 || transferResult.sourceDeleteWarningCount > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        viewModel.consumeTransferCompletion()
    }

    LuckyFilesTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val overlayOpen = showSettings || actionMenuItem != null || renameItem != null || deleteItem != null || multiDeleteItems != null || showNewFolderDialog || propertiesItem != null || operationMessage != null || uiState.transferProgress != null || uiState.conflictRequest != null

                val transferActionLabel = when (uiState.transferMode) {
                    TransferMode.COPY -> if (uiState.transferSources.size > 1) context.getString(R.string.copy_here_count, uiState.transferSources.size) else context.getString(R.string.copy_here)
                    TransferMode.MOVE -> if (uiState.transferSources.size > 1) context.getString(R.string.move_here_count, uiState.transferSources.size) else context.getString(R.string.move_here)
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
                    canCreateFolder = uiState.currentPath != null && uiState.transferMode == null && !selectionMode,
                    transferActionLabel = if (uiState.currentPath != null) transferActionLabel else null,
                    selectionMode = selectionMode,
                    selectedPaths = selectedPaths,
                    onItemFocused = { viewModel.setFocusedPath(it.path) },
                    onItemClick = { item ->
                        if (overlayOpen) return@BrowserScreen
                        if (uiState.transferMode != null) {
                            if (item is BrowserItem.Folder || item is BrowserItem.Storage) {
                                if (item is BrowserItem.Storage) viewModel.setCurrentStorageRoot(item.path)
                                viewModel.openDirectory(item.path)
                            }
                            return@BrowserScreen
                        }
                        actionHandler.handleItemClick(item, selectionMode, ::toggleSelection)
                        if (!selectionMode && item is BrowserItem.File) {
                            val opened = FileOpener.open(context, item.path)
                            if (!opened) Toast.makeText(context, context.getString(R.string.no_app_to_open), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onItemLongClick = { item ->
                        if (overlayOpen || uiState.transferMode != null || item is BrowserItem.Storage) return@BrowserScreen
                        if (selectionMode) { toggleSelection(item) } else { actionMenuItem = item }
                    },
                    onCreateFolderClick = { if (!overlayOpen && uiState.currentPath != null && !selectionMode) showNewFolderDialog = true },
                    onTransferHereClick = { uiState.currentPath?.let { viewModel.startPreparedTransfer(it) } },
                    onTransferCancelClick = { viewModel.cancelPreparedTransfer(); restoreBrowserFocus() },
                    onSelectAllClick = { selectedPaths = uiState.browserItems.filter { it !is BrowserItem.Storage }.map { it.path }.toSet() },
                    onSelectionCopyClick = { actionHandler.startTransfer(TransferMode.COPY, selectedItems()) { selectionMode = false; selectedPaths = emptySet(); restoreBrowserFocus() } },
                    onSelectionMoveClick = { actionHandler.startTransfer(TransferMode.MOVE, selectedItems()) { selectionMode = false; selectedPaths = emptySet(); restoreBrowserFocus() } },
                    onSelectionDeleteClick = {
                        val items = selectedItems()
                        if (items.isEmpty()) { Toast.makeText(context, context.getString(R.string.no_items_selected), Toast.LENGTH_SHORT).show() }
                        else { multiDeleteItems = items }
                    },
                    onSelectionCancelClick = { cancelSelection() },
                    onSettingsClick = { if (!overlayOpen && !selectionMode) showSettings = true }
                )

                actionMenuItem?.let { item ->
                    ItemActionMenuOverlay(
                        item = item,
                        onSelect = { actionMenuItem = null; selectionMode = true; selectedPaths = setOf(item.path); restoreBrowserFocus(item.path) },
                        onRename = { actionMenuItem = null; renameItem = item },
                        onCopy = { actionMenuItem = null; actionHandler.startTransfer(TransferMode.COPY, listOf(item)) { restoreBrowserFocus(item.path) } },
                        onMove = { actionMenuItem = null; actionHandler.startTransfer(TransferMode.MOVE, listOf(item)) { restoreBrowserFocus(item.path) } },
                        onDelete = { actionMenuItem = null; deleteItem = item },
                        onProperties = { actionMenuItem = null; openProperties(item) },
                        onDismiss = { actionMenuItem = null; restoreBrowserFocus(item.path) }
                    )
                }

                propertiesItem?.let { item ->
                    PropertiesOverlay(itemName = item.name, properties = propertiesData, error = propertiesError, onDismiss = { propertiesJob?.cancel(); propertiesJob = null; propertiesItem = null; propertiesData = null; propertiesError = null; restoreBrowserFocus(item.path) })
                }

                renameItem?.let { item ->
                    NameInputOverlay(title = context.getString(R.string.rename), initialValue = item.name, confirmLabel = context.getString(R.string.save), onConfirm = { actionHandler.rename(item, it) { renameItem = null; restoreBrowserFocus(it) } }, onDismiss = { renameItem = null; restoreBrowserFocus(item.path) })
                }

                deleteItem?.let { item ->
                    DeleteConfirmOverlay(
                        item = item,
                        onConfirm = {
                            deleteItem = null
                            operationMessage = context.getString(R.string.deleting_item, item.name)
                            actionHandler.delete(listOf(item)) { successCount, failureCount ->
                                operationMessage = null
                                restoreBrowserFocus()
                                val message = if (failureCount == 0) {
                                    context.getString(R.string.deleted)
                                } else {
                                    context.resources.getQuantityString(R.plurals.delete_summary, successCount, successCount, failureCount)
                                }
                                Toast.makeText(
                                    context,
                                    message,
                                    if (failureCount == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onDismiss = {
                            deleteItem = null
                            restoreBrowserFocus(item.path)
                        }
                    )
                }

                multiDeleteItems?.let { items ->
                    MultiDeleteConfirmOverlay(
                        count = items.size,
                        onConfirm = {
                            multiDeleteItems = null
                            selectionMode = false
                            selectedPaths = emptySet()
                            scope.launch {
                                var successCount = 0
                                var failureCount = 0
                                items.forEachIndexed { index, item ->
                                    operationMessage = context.getString(R.string.deleting_item_progress, index + 1, items.size, item.name)
                                    viewModel.delete(item.path).onSuccess { successCount++ }.onFailure { failureCount++ }
                                }
                                operationMessage = null
                                viewModel.refreshCurrentDirectory()
                                restoreBrowserFocus()
                                if (failureCount == 0) { Toast.makeText(context, context.resources.getQuantityString(R.plurals.items_deleted, successCount, successCount), Toast.LENGTH_SHORT).show() }
                                else { Toast.makeText(context, context.resources.getQuantityString(R.plurals.delete_summary, successCount, successCount, failureCount), Toast.LENGTH_LONG).show() }
                            }
                        },
                        onDismiss = { multiDeleteItems = null; restoreBrowserFocus() }
                    )
                }

                if (showNewFolderDialog) {
                    NameInputOverlay(title = context.getString(R.string.new_folder), initialValue = "", confirmLabel = context.getString(R.string.create), onConfirm = { name -> uiState.currentPath?.let { parentPath -> actionHandler.createFolder(parentPath, name) { createdPath -> showNewFolderDialog = false; restoreBrowserFocus(createdPath) } } }, onDismiss = { showNewFolderDialog = false; restoreBrowserFocus() })
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
                        onDismiss = { showSettings = false; restoreBrowserFocus() }
                    )
                }

                uiState.conflictRequest?.let { request ->
                    TransferConflictOverlay(sourceName = request.sourceName, targetDirectory = request.targetDirectory, multipleItems = request.multipleItems, onDecision = { policy, applyToAll -> viewModel.answerTransferConflict(TransferConflictAnswer(policy = policy, applyToAll = applyToAll, cancelled = false)) }, onCancel = { viewModel.answerTransferConflict(TransferConflictAnswer(policy = null, applyToAll = false, cancelled = true)) })
                }

                uiState.transferProgress?.let { progress ->
                    TransferProgressOverlay(title = progress.title, currentItem = progress.currentItem, totalItems = progress.totalItems, currentName = progress.currentName, bytesProcessed = progress.bytesProcessed, totalBytes = progress.totalBytes, bytesPerSecond = progress.bytesPerSecond, onCancel = viewModel::cancelRunningTransfer)
                }

                operationMessage?.let { OperationProgressOverlay(message = it) }

                BackHandler(enabled = uiState.currentPath != null && !overlayOpen && !selectionMode) { viewModel.navigateBack() }
                BackHandler(enabled = uiState.currentPath == null && uiState.transferMode != null && !overlayOpen) { viewModel.cancelPreparedTransfer(); restoreBrowserFocus() }
                BackHandler(enabled = selectionMode && !overlayOpen) { cancelSelection() }
            }
        }
    }
}
