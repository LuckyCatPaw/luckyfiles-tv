package com.luckycatpaw.luckyfilestv.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.ui.common.BrowserHeader
import com.luckycatpaw.luckyfilestv.ui.common.HeaderButtonConfig
import com.luckycatpaw.luckyfilestv.ui.common.HeaderFocusTarget
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGrid
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGridDefaults
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.common.rememberBrowserFocusState
import com.luckycatpaw.luckyfilestv.ui.common.rememberTvFileGridFocusState


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
    showCancelAction: Boolean = false,
    showSettingsAction: Boolean = true,
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
    onSettingsClick: () -> Unit = {},
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

    val safeSpace = TvFileGridDefaults.SafeHorizontalSpace
    val availableWidth = (configuration.screenWidthDp.dp - (safeSpace * 2))
    val columnCount = TvFileGridDefaults.columnCount(availableWidth)

    // Key focus state by stable paths. Repository refreshes may return a new list
    // instance containing the same files and must not reset focus.
    val itemPaths = remember(items) {
        items.map { it.path }
    }

    val gridFocusState = rememberTvFileGridFocusState(itemPaths)

    val createFolderFocusRequester = remember { FocusRequester() }
    val transferCancelFocusRequester = remember { FocusRequester() }
    val transferHereFocusRequester = remember { FocusRequester() }

    val selectAllFocusRequester = remember { FocusRequester() }
    val selectionCopyFocusRequester = remember { FocusRequester() }
    val selectionMoveFocusRequester = remember { FocusRequester() }
    val selectionDeleteFocusRequester = remember { FocusRequester() }
    val selectionCancelFocusRequester = remember { FocusRequester() }

    val cancelFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }

    // Resolve an explicit return target before the new list is first rendered.
    // This prevents a one-frame highlight on a stale index during back navigation.
    val explicitFocusIndex = focusPath?.let(itemPaths::indexOf) ?: -1
    val initialFocusIndex = when {
        explicitFocusIndex >= 0 -> explicitFocusIndex
        initialGridPosition != null && items.isNotEmpty() -> initialGridIndex
        else -> -1
    }

    val focusState = rememberBrowserFocusState<String, HeaderFocusTarget>(
        keys = itemPaths,
        gridFocusState = gridFocusState,
        gridState = gridState,
        scope = scope,
        initialFocusIndex = initialFocusIndex
    )

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

    fun requestedFocusIndex(): Int {
        val requested = focusPath?.let { path ->
            itemPaths.indexOf(path)
        } ?: -1

        return when {
            requested >= 0 -> requested
            focusPath != null -> -1
            focusState.selectedIndex in items.indices -> focusState.selectedIndex
            focusState.focusedIndex in items.indices -> focusState.focusedIndex
            items.isNotEmpty() -> 0
            else -> -1
        }
    }

    fun requesterForHeaderTarget(target: HeaderFocusTarget): FocusRequester? {
        if (!focusEnabled) return null

        return when (target) {
            HeaderFocusTarget.CREATE_FOLDER ->
                createFolderFocusRequester.takeIf {
                    !selectionMode && canCreateFolder
                }

            HeaderFocusTarget.TRANSFER_CANCEL ->
                transferCancelFocusRequester.takeIf {
                    !selectionMode && transferActionLabel != null
                }

            HeaderFocusTarget.TRANSFER_HERE ->
                transferHereFocusRequester.takeIf {
                    !selectionMode && transferActionLabel != null
                }

            HeaderFocusTarget.SELECT_ALL ->
                selectAllFocusRequester.takeIf {
                    selectionMode
                }

            HeaderFocusTarget.SELECTION_COPY ->
                selectionCopyFocusRequester.takeIf {
                    selectionMode
                }

            HeaderFocusTarget.SELECTION_MOVE ->
                selectionMoveFocusRequester.takeIf {
                    selectionMode
                }

            HeaderFocusTarget.SELECTION_DELETE ->
                selectionDeleteFocusRequester.takeIf {
                    selectionMode
                }

            HeaderFocusTarget.SELECTION_CANCEL ->
                selectionCancelFocusRequester.takeIf {
                    selectionMode
                }

            HeaderFocusTarget.CANCEL ->
                cancelFocusRequester.takeIf {
                    !selectionMode &&
                            transferActionLabel == null &&
                            showCancelAction
                }

            HeaderFocusTarget.SETTINGS ->
                settingsFocusRequester.takeIf {
                    !selectionMode && showSettingsAction
                }
            else -> null
        }
    }

    fun firstHeaderFocusRequester(): FocusRequester? {
        if (!focusEnabled) return null

        return if (selectionMode) {
            selectAllFocusRequester
        } else {
            when {
                canCreateFolder -> createFolderFocusRequester
                transferActionLabel != null -> transferCancelFocusRequester
                showCancelAction -> cancelFocusRequester
                showSettingsAction -> settingsFocusRequester
                else -> null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = safeSpace,
                vertical = safeSpace
            )
    ) {
        BrowserHeader(
            title = title,
            focusEnabled = focusEnabled,
            buttons = listOf(
                HeaderButtonConfig(
                    text = stringResource(R.string.all),
                    focusRequester = selectAllFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.SELECT_ALL) },
                    onClick = onSelectAllClick,
                    visible = selectionMode
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.copy),
                    focusRequester = selectionCopyFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.SELECTION_COPY) },
                    onClick = onSelectionCopyClick,
                    visible = selectionMode
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.move),
                    focusRequester = selectionMoveFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.SELECTION_MOVE) },
                    onClick = onSelectionMoveClick,
                    visible = selectionMode
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.delete),
                    focusRequester = selectionDeleteFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.SELECTION_DELETE) },
                    onClick = onSelectionDeleteClick,
                    visible = selectionMode
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.cancel),
                    focusRequester = selectionCancelFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.SELECTION_CANCEL) },
                    onClick = onSelectionCancelClick,
                    visible = selectionMode
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.new_folder),
                    focusRequester = createFolderFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.CREATE_FOLDER) },
                    onClick = onCreateFolderClick,
                    visible = !selectionMode && canCreateFolder
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.cancel),
                    focusRequester = transferCancelFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.TRANSFER_CANCEL) },
                    onClick = onTransferCancelClick,
                    visible = !selectionMode && transferActionLabel != null
                ),
                HeaderButtonConfig(
                    text = transferActionLabel,
                    focusRequester = transferHereFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.TRANSFER_HERE) },
                    onClick = onTransferHereClick,
                    visible = !selectionMode && transferActionLabel != null
                ),
                HeaderButtonConfig(
                    text = stringResource(R.string.cancel),
                    focusRequester = cancelFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.CANCEL) },
                    onClick = onTransferCancelClick,
                    visible = !selectionMode && showCancelAction && transferActionLabel == null
                ),
                HeaderButtonConfig(
                    icon = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings),
                    focusRequester = settingsFocusRequester,
                    onFocused = { focusState.onHeaderFocused(HeaderFocusTarget.SETTINGS) },
                    onClick = onSettingsClick,
                    visible = !selectionMode && showSettingsAction
                )
            ),
            onDown = { focusState.restoreGridFocus(focusEnabled) }
        )

        Spacer(
            modifier = Modifier.height(14.dp)
        )

        TvFileGrid(
            items = items,
            itemKey = { item -> item.path },
            gridState = gridState,
            focusState = gridFocusState,
            columnCount = columnCount,
            selectedIndex = focusState.selectedIndex,
            selectionVisible = focusState.gridSelectionVisible,
            enabled = focusEnabled,
            isMarked = { item -> item.path in selectedPaths },
            onSelectionChanged = { index, _ ->
                focusState.onSelectionChanged(index)
                onItemFocused(items[index])
            },
            onExitUp = {
                focusState.onExitUp(firstHeaderFocusRequester())
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
        itemPaths,
        focusPath,
        focusRequestKey,
        columnCount,
        focusEnabled
    ) {
        focusState.applyInitialFocus(
            targetIndex = requestedFocusIndex(),
            enabled = focusEnabled,
            onItemFocused = { index -> onItemFocused(items[index]) }
        ) { target ->
            target?.let { requesterForHeaderTarget(it) } ?: firstHeaderFocusRequester()
        }
    }
}

