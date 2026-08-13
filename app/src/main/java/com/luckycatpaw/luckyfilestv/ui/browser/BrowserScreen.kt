package com.luckycatpaw.luckyfilestv.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.ui.common.BrowserHeaderButton
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGrid
import com.luckycatpaw.luckyfilestv.ui.common.TvFileGridDefaults
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition
import com.luckycatpaw.luckyfilestv.ui.common.rememberBrowserFocusState
import com.luckycatpaw.luckyfilestv.ui.common.rememberTvFileGridFocusState

private enum class HeaderFocusTarget {
    CREATE_FOLDER,
    TRANSFER_CANCEL,
    TRANSFER_HERE,
    SELECT_ALL,
    SELECTION_COPY,
    SELECTION_MOVE,
    SELECTION_DELETE,
    SELECTION_CANCEL,
    CANCEL,
    SETTINGS
}

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
            selectionMode = selectionMode,
            canCreateFolder = canCreateFolder,
            transferActionLabel = transferActionLabel,
            showCancelAction = showCancelAction,
            showSettingsAction = showSettingsAction,
            createFolderFocusRequester = createFolderFocusRequester,
            transferCancelFocusRequester = transferCancelFocusRequester,
            transferHereFocusRequester = transferHereFocusRequester,
            selectAllFocusRequester = selectAllFocusRequester,
            selectionCopyFocusRequester = selectionCopyFocusRequester,
            selectionMoveFocusRequester = selectionMoveFocusRequester,
            selectionDeleteFocusRequester = selectionDeleteFocusRequester,
            selectionCancelFocusRequester = selectionCancelFocusRequester,
            cancelFocusRequester = cancelFocusRequester,
            settingsFocusRequester = settingsFocusRequester,
            onHeaderFocused = { target ->
                focusState.onHeaderFocused(target)
            },
            onHeaderDown = {
                focusState.restoreGridFocus(focusEnabled)
            },
            onCreateFolderClick = onCreateFolderClick,
            onTransferHereClick = onTransferHereClick,
            onTransferCancelClick = onTransferCancelClick,
            onSelectAllClick = onSelectAllClick,
            onSelectionCopyClick = onSelectionCopyClick,
            onSelectionMoveClick = onSelectionMoveClick,
            onSelectionDeleteClick = onSelectionDeleteClick,
            onSelectionCancelClick = onSelectionCancelClick,
            onCancelClick = onTransferCancelClick,
            onSettingsClick = onSettingsClick
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

@Composable
private fun BrowserHeader(
    title: String,
    focusEnabled: Boolean,
    selectionMode: Boolean,
    canCreateFolder: Boolean,
    transferActionLabel: String?,
    showCancelAction: Boolean,
    showSettingsAction: Boolean,
    createFolderFocusRequester: FocusRequester,
    transferCancelFocusRequester: FocusRequester,
    transferHereFocusRequester: FocusRequester,
    selectAllFocusRequester: FocusRequester,
    selectionCopyFocusRequester: FocusRequester,
    selectionMoveFocusRequester: FocusRequester,
    selectionDeleteFocusRequester: FocusRequester,
    selectionCancelFocusRequester: FocusRequester,
    cancelFocusRequester: FocusRequester,
    settingsFocusRequester: FocusRequester,
    onHeaderFocused: (HeaderFocusTarget) -> Unit,
    onHeaderDown: () -> Unit,
    onCreateFolderClick: () -> Unit,
    onTransferHereClick: () -> Unit,
    onTransferCancelClick: () -> Unit,
    onSelectAllClick: () -> Unit,
    onSelectionCopyClick: () -> Unit,
    onSelectionMoveClick: () -> Unit,
    onSelectionDeleteClick: () -> Unit,
    onSelectionCancelClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSettingsClick: () -> Unit
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

        if (selectionMode) {
            BrowserHeaderButton(
                text = stringResource(R.string.all),
                focusEnabled = focusEnabled,
                focusRequester = selectAllFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.SELECT_ALL) },
                onDown = onHeaderDown,
                onClick = onSelectAllClick
            )

            BrowserHeaderButton(
                text = stringResource(R.string.copy),
                focusEnabled = focusEnabled,
                focusRequester = selectionCopyFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.SELECTION_COPY) },
                onDown = onHeaderDown,
                onClick = onSelectionCopyClick
            )

            BrowserHeaderButton(
                text = stringResource(R.string.move),
                focusEnabled = focusEnabled,
                focusRequester = selectionMoveFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.SELECTION_MOVE) },
                onDown = onHeaderDown,
                onClick = onSelectionMoveClick
            )

            BrowserHeaderButton(
                text = stringResource(R.string.delete),
                focusEnabled = focusEnabled,
                focusRequester = selectionDeleteFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.SELECTION_DELETE) },
                onDown = onHeaderDown,
                onClick = onSelectionDeleteClick
            )

            BrowserHeaderButton(
                text = stringResource(R.string.cancel),
                focusEnabled = focusEnabled,
                focusRequester = selectionCancelFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.SELECTION_CANCEL) },
                onDown = onHeaderDown,
                onClick = onSelectionCancelClick
            )

            return@Row
        }

        if (canCreateFolder) {
            BrowserHeaderButton(
                text = stringResource(R.string.new_folder),
                focusEnabled = focusEnabled,
                focusRequester = createFolderFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.CREATE_FOLDER) },
                onDown = onHeaderDown,
                onClick = onCreateFolderClick
            )
        }

        if (transferActionLabel != null) {
            BrowserHeaderButton(
                text = stringResource(R.string.cancel),
                focusEnabled = focusEnabled,
                focusRequester = transferCancelFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.TRANSFER_CANCEL) },
                onDown = onHeaderDown,
                onClick = onTransferCancelClick
            )

            BrowserHeaderButton(
                text = transferActionLabel,
                focusEnabled = focusEnabled,
                focusRequester = transferHereFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.TRANSFER_HERE) },
                onDown = onHeaderDown,
                onClick = onTransferHereClick
            )
        } else if (showCancelAction) {
            BrowserHeaderButton(
                text = stringResource(R.string.cancel),
                focusEnabled = focusEnabled,
                focusRequester = cancelFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.CANCEL) },
                onDown = onHeaderDown,
                onClick = onCancelClick
            )
        }

        if (showSettingsAction) {
            BrowserHeaderButton(
                icon = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings),
                focusEnabled = focusEnabled,
                focusRequester = settingsFocusRequester,
                onFocused = { onHeaderFocused(HeaderFocusTarget.SETTINGS) },
                onDown = onHeaderDown,
                onClick = onSettingsClick
            )
        }
    }
}
