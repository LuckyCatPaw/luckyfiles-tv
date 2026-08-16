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

    fun startTransfer(mode: TransferMode, items: List<BrowserItem>, onStarted: () -> Unit) {
        if (items.isEmpty()) return
        viewModel.prepareTransfer(mode, items)
        onStarted()
    }

    fun rename(item: BrowserItem, newName: String, onFinished: () -> Unit) {
        modelScope.launch {
            viewModel.rename(item.path, newName)
                .onSuccess {
                    onFinished()
                    viewModel.refreshCurrentDirectory(focusPath = it)
                }
                .onFailure { error -> toast(error.message, R.string.rename_failed) }
        }
    }

    /**
     * Deletes [items] one by one. [onProgress] receives the item about to be deleted and
     * its 1-based position, which lets the caller drive a progress overlay without
     * running its own coroutine.
     */
    fun delete(
        items: List<BrowserItem>,
        onProgress: (index: Int, total: Int, item: BrowserItem) -> Unit = { _, _, _ -> },
        onFinished: (successCount: Int, failureCount: Int) -> Unit
    ) {
        if (items.isEmpty()) return

        modelScope.launch {
            var successCount = 0
            var failureCount = 0

            items.forEachIndexed { index, item ->
                onProgress(index + 1, items.size, item)
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
            viewModel.createFolder(parentPath, name)
                .onSuccess { newPath ->
                    onFinished(newPath)
                    viewModel.refreshCurrentDirectory(focusPath = newPath)
                }
                .onFailure { error -> toast(error.message, R.string.folder_create_failed) }
        }
    }

    private fun toast(message: String?, fallbackResId: Int) {
        Toast.makeText(
            appContext,
            message ?: appContext.getString(fallbackResId),
            Toast.LENGTH_LONG
        ).show()
    }
}
