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
    private val viewModel: MainViewModel
) {
    fun handleItemClick(item: BrowserItem, selectionMode: Boolean, toggleSelection: (BrowserItem) -> Unit) {
        if (selectionMode) {
            toggleSelection(item)
            return
        }

        when (item) {
            is BrowserItem.Storage -> {
                viewModel.setCurrentStorageRoot(item.path)
                viewModel.openDirectory(item.path)
            }
            is BrowserItem.Folder -> viewModel.openDirectory(item.path)
            is BrowserItem.File -> Unit
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

    fun delete(items: List<BrowserItem>, onFinished: (successCount: Int, failureCount: Int) -> Unit) {
        if (items.isEmpty()) return
        modelScope.launch {
            var successCount = 0
            var failureCount = 0
            items.forEach { item ->
                viewModel.delete(item.path)
                    .onSuccess { successCount++ }
                    .onFailure { failureCount++ }
            }
            if (successCount > 0) {
                viewModel.refreshCurrentDirectory()
            }
            onFinished(successCount, failureCount)
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
