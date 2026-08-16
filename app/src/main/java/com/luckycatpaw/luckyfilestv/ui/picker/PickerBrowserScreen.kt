package com.luckycatpaw.luckyfilestv.ui.picker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.browser.BrowserGridItem
import com.luckycatpaw.luckyfilestv.ui.common.BrowserHeader
import com.luckycatpaw.luckyfilestv.ui.common.HeaderButtonConfig
import com.luckycatpaw.luckyfilestv.ui.common.HeaderFocusTarget
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGrid
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGridDefaults
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.common.rememberBrowserFocusState
import com.luckycatpaw.luckyfilestv.ui.common.rememberTvFileGridFocusState
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
    showSearchAction: Boolean = false,
    showRecentsAction: Boolean = false,
    showCancelAction: Boolean = false,
    onSearchClick: () -> Unit = {},
    onRecentsClick: () -> Unit = {},
    onItemClick: (PickerBrowserItem) -> Unit,
    onItemLongClick: (PickerBrowserItem) -> Unit = {},
    onItemFocused: (PickerBrowserItem) -> Unit = {},
    onCreateFolderClick: () -> Unit = {},
    onPrimaryActionClick: () -> Unit = {},
    onCancelClick: () -> Unit = {}
) {
    val requestedInitialIndex = initialGridPosition?.firstVisibleItemIndex ?: 0
    val initialGridIndex = requestedInitialIndex.coerceIn(
        minimumValue = 0,
        maximumValue = items.lastIndex.coerceAtLeast(0)
    )
    val initialGridOffset = initialGridPosition
        ?.firstVisibleItemScrollOffset
        ?.takeIf { requestedInitialIndex == initialGridIndex }
        ?.coerceAtLeast(0)
        ?: 0
        
    val gridState = key(gridStateKey) {
        rememberLazyGridState(
            initialFirstVisibleItemIndex = initialGridIndex,
            initialFirstVisibleItemScrollOffset = initialGridOffset
        )
    }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    val itemKeys = remember(items) { items.map { it.key } }
    val gridFocusState = rememberTvFileGridFocusState(itemKeys)

    val searchFocusRequester = remember { FocusRequester() }
    val recentsFocusRequester = remember { FocusRequester() }
    val createFolderFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val primaryFocusRequester = remember { FocusRequester() }

    DisposableEffect(gridState) {
        onDispose {
            onGridPositionChanged(
                TvGridPosition(
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset
                )
            )
        }
    }

    val focusState = rememberBrowserFocusState<String, HeaderFocusTarget>(
        itemKeys,
        gridFocusState,
        gridState,
        scope,
        initialGridIndex
    )

    fun headerFocusRequester(target: HeaderFocusTarget?): FocusRequester? {
        return target?.let {
            when (it) {
                HeaderFocusTarget.SEARCH -> searchFocusRequester.takeIf { showSearchAction }
                HeaderFocusTarget.RECENTS -> recentsFocusRequester.takeIf { showRecentsAction }
                HeaderFocusTarget.CREATE_FOLDER -> createFolderFocusRequester.takeIf { canCreateFolder }
                HeaderFocusTarget.CANCEL -> cancelFocusRequester.takeIf { showCancelAction }
                HeaderFocusTarget.PRIMARY -> primaryFocusRequester.takeIf { primaryActionLabel != null }
                else -> null
            }
        } ?: listOf(
            searchFocusRequester.takeIf { showSearchAction },
            recentsFocusRequester.takeIf { showRecentsAction },
            createFolderFocusRequester.takeIf { canCreateFolder },
            cancelFocusRequester.takeIf { showCancelAction },
            primaryFocusRequester.takeIf { primaryActionLabel != null }
        ).firstOrNull { it != null }
    }

    val safeSpace = TvFileGridDefaults.SafeHorizontalSpace
    val availableWidth = configuration.screenWidthDp.dp - safeSpace * 2
    val columnCount = TvFileGridDefaults.columnCount(availableWidth)

    Column(modifier = Modifier.fillMaxSize()) {
        BrowserHeader(
            title = title,
            onDown = { focusState.restoreGridFocus() },
            buttons = listOf(
                HeaderButtonConfig(
                    text = stringResource(R.string.search),
                    focusRequester = searchFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.SEARCH) },
                    onClick = onSearchClick,
                    visible = showSearchAction
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.recents),
                    focusRequester = recentsFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.RECENTS) },
                    onClick = onRecentsClick,
                    visible = showRecentsAction
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.new_folder),
                    focusRequester = createFolderFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.CREATE_FOLDER) },
                    onClick = onCreateFolderClick,
                    visible = canCreateFolder
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.cancel),
                    focusRequester = cancelFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.CANCEL) },
                    onClick = onCancelClick,
                    visible = showCancelAction
                ),
                HeaderButtonConfig(
                    text = primaryActionLabel,
                    focusRequester = primaryFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.PRIMARY) },
                    onClick = onPrimaryActionClick,
                    visible = primaryActionLabel != null
                )
            )
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp)
        ) {
            TvFileGrid(
                items = items,
                itemKey = { it.key },
                gridState = gridState,
                focusState = gridFocusState,
                columnCount = columnCount,
                selectedIndex = focusState.selectedIndex,
                selectionVisible = focusState.gridSelectionVisible,
                onSelectionChanged = { index, _ -> focusState.onSelectionChanged(index) },
                onExitUp = {
                    focusState.onExitUp(headerFocusRequester(focusState.lastHeaderTarget))
                },
                onItemClick = onItemClick,
                onItemLongClick = onItemLongClick,
                onItemFocused = { index, item ->
                    focusState.onGridFocused(index)
                    onItemFocused(item)
                },
                isMarked = { it.key in selectedKeys },
                modifier = Modifier.fillMaxSize(),
                itemContent = { item, selected, onClick, onFocused, modifier ->
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
            )
        }
    }

    val explicitFocusIndex = remember(focusKey, items) {
        focusKey?.let { key -> itemKeys.indexOf(key) } ?: -1
    }

    LaunchedEffect(focusRequestKey, items) {
        focusState.applyInitialFocus(
            targetIndex = explicitFocusIndex.takeIf { it >= 0 }
                ?: if (items.isNotEmpty()) initialGridIndex.coerceIn(items.indices) else -1,
            enabled = true,
            onItemFocused = { index -> onItemFocused(items[index]) },
            headerRequester = { target -> headerFocusRequester(target) }
        )
    }
}
