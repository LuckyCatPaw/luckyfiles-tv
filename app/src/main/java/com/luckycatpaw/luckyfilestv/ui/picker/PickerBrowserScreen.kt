package com.luckycatpaw.luckyfilestv.ui.picker

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.browser.BrowserGridItem
import com.luckycatpaw.luckyfilestv.ui.common.BrowserHeaderAction
import com.luckycatpaw.luckyfilestv.ui.common.HeaderFocusTarget
import com.luckycatpaw.luckyfilestv.ui.common.TvBrowserScaffold
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem

@Composable
internal fun PickerBrowserScreen(
    items: List<PickerBrowserItem>,
    title: String,
    optimizeFileNames: Boolean,
    useFolderJpgAsIcon: Boolean,
    focusKey: String? = null,
    focusRequestKey: Int = 0,
    gridStateKey: Any? = null,
    initialGridPosition: TvGridPosition? = null,
    onGridPositionChanged: (TvGridPosition) -> Unit = {},
    selectedKeys: Set<String> = emptySet(),
    canCreateFolder: Boolean = false,
    primaryActionLabel: String? = null,
    showRecentsAction: Boolean = false,
    onSearchClick: () -> Unit = {},
    onRecentsClick: () -> Unit = {},
    onItemClick: (PickerBrowserItem) -> Unit,
    onItemLongClick: (PickerBrowserItem) -> Unit = {},
    onItemFocused: (PickerBrowserItem) -> Unit = {},
    onCreateFolderClick: () -> Unit = {},
    onPrimaryActionClick: () -> Unit = {},
    onCancelClick: () -> Unit = {}
) {
    val searchLabel = stringResource(R.string.search)
    val recentsLabel = stringResource(R.string.recents)
    val newFolderLabel = stringResource(R.string.new_folder)
    val cancelLabel = stringResource(R.string.cancel)

    val headerActions =
        remember(
            showRecentsAction,
            canCreateFolder,
            primaryActionLabel,
            onSearchClick,
            onRecentsClick,
            onCreateFolderClick,
            onCancelClick,
            onPrimaryActionClick
        ) {
            listOf(
                BrowserHeaderAction(
                    target = HeaderFocusTarget.SEARCH,
                    text = searchLabel,
                    onClick = onSearchClick
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.RECENTS,
                    text = recentsLabel,
                    onClick = onRecentsClick,
                    visible = showRecentsAction
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.CREATE_FOLDER,
                    text = newFolderLabel,
                    onClick = onCreateFolderClick,
                    visible = canCreateFolder
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.CANCEL,
                    text = cancelLabel,
                    onClick = onCancelClick
                ),
                BrowserHeaderAction(
                    target = HeaderFocusTarget.PRIMARY,
                    text = primaryActionLabel,
                    onClick = onPrimaryActionClick,
                    visible = primaryActionLabel != null
                )
            )
        }

    TvBrowserScaffold(
        items = items,
        itemKey = { it.key },
        title = title,
        headerActions = headerActions,
        onItemClick = onItemClick,
        focusKey = focusKey,
        focusRequestKey = focusRequestKey,
        gridStateKey = gridStateKey,
        initialGridPosition = initialGridPosition,
        onGridPositionChanged = onGridPositionChanged,
        markedKeys = selectedKeys,
        // The picker draws edge to edge; the browser applies TV safe space instead.
        contentPadding = PaddingValues(0.dp),
        gridSpacing = 3.dp,
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
