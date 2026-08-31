package com.luckycatpaw.luckyfilestv.ui.browser

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.ui.common.BrowserHeaderAction
import com.luckycatpaw.luckyfilestv.ui.common.HeaderFocusTarget
import com.luckycatpaw.luckyfilestv.ui.common.TvBrowserScaffold
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGridDefaults
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition

@Composable
internal fun BrowserScreen(
    items: List<BrowserItem>,
    title: String,
    optimizeFileNames: Boolean = false,
    useFolderJpgAsIcon: Boolean = true,
    focusPath: String? = null,
    focusRequestKey: Int = 0,
    gridStateKey: Any? = Unit,
    initialGridPosition: TvGridPosition? = null,
    onGridPositionChanged: (TvGridPosition) -> Unit = {},
    focusEnabled: Boolean = true,
    canCreateFolder: Boolean = false,
    transferActionLabel: String? = null,
    selectionMode: Boolean = false,
    selectedPaths: Set<String> = emptySet(),
    onItemClick: (BrowserItem) -> Unit,
    onItemLongClick: (BrowserItem) -> Unit = {},
    onItemFocused: (BrowserItem) -> Unit = {},
    onCreateFolderClick: () -> Unit = {},
    onTransferHereClick: () -> Unit = {},
    onTransferCancelClick: () -> Unit = {},
    onSelectAllClick: () -> Unit = {},
    onSelectionCopyClick: () -> Unit = {},
    onSelectionMoveClick: () -> Unit = {},
    onSelectionDeleteClick: () -> Unit = {},
    onSelectionCancelClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    val transferPending = transferActionLabel != null
    val safeSpace = TvFileGridDefaults.SafeHorizontalSpace

    val allLabel = stringResource(R.string.all)
    val copyLabel = stringResource(R.string.copy)
    val moveLabel = stringResource(R.string.move)
    val deleteLabel = stringResource(R.string.delete)
    val cancelLabel = stringResource(R.string.cancel)
    val newFolderLabel = stringResource(R.string.new_folder)
    val settingsLabel = stringResource(R.string.settings)

    // Nine objects that only depend on the flags below. Rebuilding them on every
    // recomposition also handed TvBrowserScaffold a fresh list instance every time, which
    // kept BrowserHeader and its buttons from ever skipping.
    val headerActions =
        remember(
            selectionMode,
            canCreateFolder,
            transferPending,
            transferActionLabel,
            onSelectAllClick,
            onSelectionCopyClick,
            onSelectionMoveClick,
            onSelectionDeleteClick,
            onSelectionCancelClick,
            onCreateFolderClick,
            onTransferCancelClick,
            onTransferHereClick,
            onSettingsClick
        ) {
            listOf(
                BrowserHeaderAction(
                    target = HeaderFocusTarget.SELECT_ALL,
                    text = allLabel,
                    onClick = onSelectAllClick,
                    visible = selectionMode
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.SELECTION_COPY,
                    text = copyLabel,
                    onClick = onSelectionCopyClick,
                    visible = selectionMode
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.SELECTION_MOVE,
                    text = moveLabel,
                    onClick = onSelectionMoveClick,
                    visible = selectionMode
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.SELECTION_DELETE,
                    text = deleteLabel,
                    onClick = onSelectionDeleteClick,
                    visible = selectionMode
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.SELECTION_CANCEL,
                    text = cancelLabel,
                    onClick = onSelectionCancelClick,
                    visible = selectionMode
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.CREATE_FOLDER,
                    text = newFolderLabel,
                    onClick = onCreateFolderClick,
                    visible = !selectionMode && canCreateFolder
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.TRANSFER_CANCEL,
                    text = cancelLabel,
                    onClick = onTransferCancelClick,
                    visible = !selectionMode && transferPending
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.TRANSFER_HERE,
                    text = transferActionLabel,
                    onClick = onTransferHereClick,
                    visible = !selectionMode && transferPending
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.SETTINGS,
                    icon = Icons.Filled.Settings,
                    contentDescription = settingsLabel,
                    onClick = onSettingsClick,
                    visible = !selectionMode
                )
            )
        }

    TvBrowserScaffold(
        items = items,
        itemKey = { it.path },
        title = title,
        headerActions = headerActions,
        onItemClick = onItemClick,
        focusKey = focusPath,
        focusRequestKey = focusRequestKey,
        gridStateKey = gridStateKey,
        initialGridPosition = initialGridPosition,
        onGridPositionChanged = onGridPositionChanged,
        focusEnabled = focusEnabled,
        markedKeys = selectedPaths,
        contentPadding = PaddingValues(horizontal = safeSpace, vertical = safeSpace),
        onItemLongClick = onItemLongClick,
        onItemFocused = onItemFocused
    ) { item, selected, onClick, onFocused, modifier ->
        BrowserGridItem(
            item = item,
            selected = selected,
            optimizeFileNames = optimizeFileNames,
            useFolderJpgAsIcon = useFolderJpgAsIcon,
            onClick = onClick,
            onFocused = { onFocused() },
            modifier = modifier
        )
    }
}
