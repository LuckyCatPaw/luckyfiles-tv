package com.luckycatpaw.luckyfilestv.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.ui.browser.BrowserGridItem
import com.luckycatpaw.luckyfilestv.ui.common.BrowserHeaderButton
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGrid
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGridDefaults
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.common.rememberBrowserFocusState
import com.luckycatpaw.luckyfilestv.ui.common.rememberTvFileGridFocusState
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerBrowserItem
import kotlinx.coroutines.launch

private enum class PickerHeaderTarget {
    SEARCH,
    RECENTS,
    CREATE_FOLDER,
    CANCEL,
    PRIMARY
}

@Composable
internal fun PickerBrowserScreen(
    items: List<PickerBrowserItem>,
    title: String,
    optimizeFileNames: Boolean = false,
    useFolderJpgAsIcon: Boolean = true,
    focusKey: String? = null,
    focusRequestKey: Int = 0,
    gridStateKey: Any? = Unit,
    initialGridPosition: TvGridPosition? = null,
    onGridPositionChanged: (TvGridPosition) -> Unit = {},
    selectedKeys: Set<String> = emptySet(),
    canCreateFolder: Boolean = false,
    primaryActionLabel: String? = null,
    showSearchAction: Boolean = true,
    showRecentsAction: Boolean = true,
    showCancelAction: Boolean = true,
    onItemClick: (PickerBrowserItem) -> Unit,
    onItemLongClick: (PickerBrowserItem) -> Unit = {},
    onItemFocused: (PickerBrowserItem) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onRecentsClick: () -> Unit = {},
    onCreateFolderClick: () -> Unit = {},
    onPrimaryActionClick: () -> Unit = {},
    onCancelClick: () -> Unit = {},
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

    val itemKeys = remember(items) {
        items.map { it.key }
    }

    val gridFocusState = rememberTvFileGridFocusState(itemKeys)
    val explicitFocusIndex = focusKey?.let(itemKeys::indexOf) ?: -1
    val initialFocusIndex = when {
        explicitFocusIndex >= 0 -> explicitFocusIndex
        (initialGridPosition != null && items.isNotEmpty()) -> initialGridIndex
        else -> -1
    }

    val focusState = rememberBrowserFocusState<String, PickerHeaderTarget>(
        keys = itemKeys,
        gridFocusState = gridFocusState,
        gridState = gridState,
        scope = scope,
        initialFocusIndex = initialFocusIndex
    )

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
                    firstVisibleItemScrollOffset =
                        gridState.firstVisibleItemScrollOffset
                )
            )
        }
    }

    fun headerFocusRequester(target: PickerHeaderTarget?): FocusRequester? {
        return target?.let {
            when (it) {
                PickerHeaderTarget.SEARCH -> searchFocusRequester.takeIf { showSearchAction }
                PickerHeaderTarget.RECENTS -> recentsFocusRequester.takeIf { showRecentsAction }
                PickerHeaderTarget.CREATE_FOLDER -> createFolderFocusRequester.takeIf { canCreateFolder }
                PickerHeaderTarget.CANCEL -> cancelFocusRequester.takeIf { showCancelAction }
                PickerHeaderTarget.PRIMARY -> primaryFocusRequester.takeIf { primaryActionLabel != null }
            }
        } ?: when {
            showSearchAction -> searchFocusRequester
            showRecentsAction -> recentsFocusRequester
            canCreateFolder -> createFolderFocusRequester
            showCancelAction -> cancelFocusRequester
            primaryActionLabel != null -> primaryFocusRequester
            else -> null
        }
    }

    val safeSpace = TvFileGridDefaults.SafeHorizontalSpace
    val availableWidth = configuration.screenWidthDp.dp - safeSpace * 2
    val columnCount = TvFileGridDefaults.columnCount(availableWidth)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = safeSpace,
                vertical = safeSpace
            )
    ) {
        PickerHeader(
            title = title,
            showSearchAction = showSearchAction,
            showRecentsAction = showRecentsAction,
            canCreateFolder = canCreateFolder,
            primaryActionLabel = primaryActionLabel,
            showCancelAction = showCancelAction,
            searchFocusRequester = searchFocusRequester,
            recentsFocusRequester = recentsFocusRequester,
            createFolderFocusRequester = createFolderFocusRequester,
            cancelFocusRequester = cancelFocusRequester,
            primaryFocusRequester = primaryFocusRequester,
            onHeaderFocused = { target ->
                focusState.onHeaderFocused(target)
            },
            onHeaderDown = {
                focusState.restoreGridFocus()
            },
            onSearchClick = onSearchClick,
            onRecentsClick = onRecentsClick,
            onCreateFolderClick = onCreateFolderClick,
            onPrimaryActionClick = onPrimaryActionClick,
            onCancelClick = onCancelClick
        )

        Spacer(
            modifier = Modifier.height(14.dp)
        )

        TvFileGrid(
            items = items,
            itemKey = { item -> item.key },
            gridState = gridState,
            focusState = gridFocusState,
            columnCount = columnCount,
            selectedIndex = focusState.selectedIndex,
            selectionVisible = focusState.gridSelectionVisible,
            isMarked = { item -> item.key in selectedKeys },
            onSelectionChanged = { index, _ ->
                focusState.onSelectionChanged(index)
                onItemFocused(items[index])
            },
            onExitUp = {
                focusState.onExitUp(headerFocusRequester(null))
            },
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick,
            onItemFocused = { index, item ->
                focusState.onGridFocused(index)
                onItemFocused(item)
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { item, selected, click, focused, modifier ->
            BrowserGridItem(
                item = item,
                selected = selected,
                optimizeFileNames = optimizeFileNames,
                useFolderJpgAsIcon = useFolderJpgAsIcon,
                onClick = click,
                onFocused = { focused() },
                modifier = modifier
            )
        }
    }

    LaunchedEffect(
        items,
        focusKey,
        focusRequestKey,
        columnCount
    ) {
        focusState.applyInitialFocus(
            targetIndex = explicitFocusIndex.takeIf { it >= 0 }
                ?: if (items.isNotEmpty()) initialGridIndex.coerceIn(items.indices) else -1,
            enabled = true,
            onItemFocused = { index -> onItemFocused(items[index]) }
        ) { target -> headerFocusRequester(target) }
    }
}

@Composable
private fun PickerHeader(
    title: String,
    showSearchAction: Boolean,
    showRecentsAction: Boolean,
    canCreateFolder: Boolean,
    primaryActionLabel: String?,
    showCancelAction: Boolean,
    searchFocusRequester: FocusRequester,
    recentsFocusRequester: FocusRequester,
    createFolderFocusRequester: FocusRequester,
    cancelFocusRequester: FocusRequester,
    primaryFocusRequester: FocusRequester,
    onHeaderFocused: (PickerHeaderTarget) -> Unit,
    onHeaderDown: () -> Unit,
    onSearchClick: () -> Unit,
    onRecentsClick: () -> Unit,
    onCreateFolderClick: () -> Unit,
    onPrimaryActionClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 21.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (showSearchAction) {
            BrowserHeaderButton(
                text = stringResource(R.string.search),
                focusRequester = searchFocusRequester,
                onFocused = { onHeaderFocused(PickerHeaderTarget.SEARCH) },
                onDown = onHeaderDown,
                onClick = onSearchClick
            )
        }

        if (showRecentsAction) {
            BrowserHeaderButton(
                text = stringResource(R.string.recents),
                focusRequester = recentsFocusRequester,
                onFocused = { onHeaderFocused(PickerHeaderTarget.RECENTS) },
                onDown = onHeaderDown,
                onClick = onRecentsClick
            )
        }

        if (canCreateFolder) {
            BrowserHeaderButton(
                text = stringResource(R.string.new_folder),
                focusRequester = createFolderFocusRequester,
                onFocused = { onHeaderFocused(PickerHeaderTarget.CREATE_FOLDER) },
                onDown = onHeaderDown,
                onClick = onCreateFolderClick
            )
        }

        if (showCancelAction) {
            BrowserHeaderButton(
                text = stringResource(R.string.cancel),
                focusRequester = cancelFocusRequester,
                onFocused = { onHeaderFocused(PickerHeaderTarget.CANCEL) },
                onDown = onHeaderDown,
                onClick = onCancelClick
            )
        }

        if (primaryActionLabel != null) {
            BrowserHeaderButton(
                text = primaryActionLabel,
                focusRequester = primaryFocusRequester,
                onFocused = { onHeaderFocused(PickerHeaderTarget.PRIMARY) },
                onDown = onHeaderDown,
                onClick = onPrimaryActionClick
            )
        }
    }
}
