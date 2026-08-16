package com.luckycatpaw.luckyfilestv.ui.main

import android.content.Context
import android.widget.Toast
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.ui.main.model.TransferMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class BrowserActionHandler(
    private val appContext: Context,
    private val modelScope: CoroutineScope,
    private val viewModel: MainViewModel,
    private val onShowProperties: (BrowserItem) -> Unit,
    @Suppress("unused") // Kept for future rename integration
    private val onShowRename: (BrowserItem) -> Unit,
    @Suppress("unused") // Kept for future delete integration
    private val onShowDelete: (BrowserItem) -> Unit
) {
    fun handleItemClick(item: BrowserItem, selectionMode: Boolean, toggleSelection: (BrowserItem) -> Unit) {
        if (selectionMode) {
            toggleSelection(item)
            return
        }

        when (item) {
            is BrowserItem.Storage -> viewModel.openDirectory(item.path)
            is BrowserItem.Folder -> viewModel.openDirectory(item.path)
            is BrowserItem.File -> onShowProperties(item)
        }
    }

    @Suppress("unused") // Entry point for selection mode on long press
    fun handleItemLongClick(item: BrowserItem, selectionMode: Boolean, toggleSelection: (BrowserItem) -> Unit) {
        if (!selectionMode) {
            toggleSelection(item)
        }
    }

    fun startTransfer(mode: TransferMode, items: List<BrowserItem>, onStarted: () -> Unit) {
        if (items.isEmpty()) return
        viewModel.prepareTransfer(mode, items)
        onStarted()
    }

    fun rename(item: BrowserItem, newName: String, onFinished: () -> Unit) {
        modelScope.launch {
            val result = viewModel.rename(item.path, newName)
            result.onSuccess {
                onFinished()
                viewModel.refreshCurrentDirectory(focusPath = it)
            }.onFailure { error ->
                Toast.makeText(appContext, error.message ?: appContext.getString(R.string.rename_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun delete(items: List<BrowserItem>, onFinished: () -> Unit) {
        if (items.isEmpty()) return
        modelScope.launch {
            var successCount = 0
            items.forEach { item ->
                viewModel.delete(item.path).onSuccess { successCount++ }
            }
            if (successCount > 0) {
                onFinished()
                viewModel.refreshCurrentDirectory()
            }
        }
    }

    fun createFolder(parentPath: String, name: String, onFinished: (String) -> Unit) {
        modelScope.launch {
            viewModel.createFolder(parentPath, name).onSuccess { newPath ->
                onFinished(newPath)
                viewModel.refreshCurrentDirectory(focusPath = newPath)
            }.onFailure { error ->
                Toast.makeText(appContext, error.message ?: appContext.getString(R.string.folder_create_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
}
