package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luckycatpaw.luckyfilestv.ui.common.model.TvGridPosition

/**
 * One header button. Unlike the older HeaderButtonConfig this carries its own
 * [target], so the scaffold can derive the focus requester from the same list that
 * decides visibility. Previously each condition existed twice — once for `visible`
 * and once in a separate `when` that mapped targets to requesters — and the two
 * could drift apart.
 */
internal data class BrowserHeaderAction(
    val target: HeaderFocusTarget,
    val onClick: () -> Unit,
    val text: String? = null,
    val icon: ImageVector? = null,
    val contentDescription: String? = null,
    val visible: Boolean = true
)

/**
 * Header, grid and D-pad focus handling shared by the file browser and the SAF picker.
 *
 * The two screens differ only in their item type, their header buttons and how an item
 * is rendered, so those are the parameters; everything else — grid position
 * save/restore, column count, focus restoration across directory changes — lives here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T : Any> TvBrowserScaffold(
    items: List<T>,
    itemKey: (T) -> String,
    title: String,
    headerActions: List<BrowserHeaderAction>,
    onItemClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    focusKey: String? = null,
    focusRequestKey: Int = 0,
    gridStateKey: Any? = Unit,
    initialGridPosition: TvGridPosition? = null,
    onGridPositionChanged: (TvGridPosition) -> Unit = {},
    focusEnabled: Boolean = true,
    markedKeys: Set<String> = emptySet(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    gridSpacing: Dp = 14.dp,
    onItemLongClick: (T) -> Unit = {},
    onItemFocused: (T) -> Unit = {},
    itemContent: @Composable (
        item: T,
        selected: Boolean,
        onClick: () -> Unit,
        onFocused: () -> Unit,
        modifier: Modifier
    ) -> Unit
) {
    val requestedInitialIndex = initialGridPosition?.firstVisibleItemIndex ?: 0
    val initialGridIndex =
        requestedInitialIndex.coerceIn(
            minimumValue = 0,
            maximumValue = items.lastIndex.coerceAtLeast(0)
        )
    val initialGridOffset =
        initialGridPosition
            ?.firstVisibleItemScrollOffset
            ?.takeIf { requestedInitialIndex == initialGridIndex }
            ?.coerceAtLeast(0)
            ?: 0

    val gridState =
        key(gridStateKey) {
            val cacheWindow = remember { LazyLayoutCacheWindow(aheadFraction = 0.5f, behindFraction = 0.2f) }
            val gridStateSaver = remember(cacheWindow) {
                listSaver(
                    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
                    restore = {
                        LazyGridState(
                            cacheWindow = cacheWindow,
                            firstVisibleItemIndex = it[0],
                            firstVisibleItemScrollOffset = it[1]
                        )
                    }
                )
            }
            rememberSaveable(saver = gridStateSaver) {
                LazyGridState(
                    cacheWindow = cacheWindow,
                    firstVisibleItemIndex = initialGridIndex,
                    firstVisibleItemScrollOffset = initialGridOffset
                )
            }
        }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current

    val safeSpace = TvFileGridDefaults.SafeHorizontalSpace
    val availableWidth = with(density) { windowInfo.containerSize.width.toDp() } - safeSpace * 2
    val columnCount = TvFileGridDefaults.columnCount(availableWidth)

    // Key focus state by stable keys. A repository refresh may return a new list
    // instance holding the same entries and must not reset focus.
    val itemKeys = remember(items) { items.map(itemKey) }
    val gridFocusState = rememberTvFileGridFocusState(itemKeys, key = gridStateKey)

    val requesters =
        remember {
            HeaderFocusTarget.entries.associateWith { FocusRequester() }
        }

    // Group-level entry points. Focus is addressed to a region, not to a widget: the
    // header restores its own last button, the grid enters at its first child.
    val headerEnterRequester = remember { FocusRequester() }
    val gridEnterRequester = remember { FocusRequester() }

    // Resolve an explicit return target before the new list is first rendered.
    // This prevents a one-frame highlight on a stale index during back navigation.
    //
    // indexOf is O(n) over the key list. Unmemoised it ran on every recomposition, so a
    // directory with a few thousand entries cost that many string comparisons per D-pad
    // press. The result only changes with the list or the requested key.
    val explicitFocusIndex = remember(itemKeys, focusKey) {
        focusKey?.let(itemKeys::indexOf) ?: -1
    }
    val initialFocusIndex =
        when {
            explicitFocusIndex >= 0 -> explicitFocusIndex
            initialGridPosition != null && items.isNotEmpty() -> initialGridIndex
            else -> -1
        }

    val focusState =
        rememberBrowserFocusState<String>(
            keys = itemKeys,
            gridFocusState = gridFocusState,
            gridState = gridState,
            scope = scope,
            initialFocusIndex = initialFocusIndex,
            key = gridStateKey
        )

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

    /** The header group, or null when it holds nothing focusable. */
    fun headerRequester(): FocusRequester? =
        headerEnterRequester.takeIf { focusEnabled && headerActions.any { it.visible } }

    fun requestedFocusIndex(): Int = when {
        explicitFocusIndex >= 0 -> explicitFocusIndex
        focusKey != null -> -1
        focusState.selectedIndex in items.indices -> focusState.selectedIndex
        focusState.focusedIndex in items.indices -> focusState.focusedIndex
        items.isNotEmpty() -> 0
        else -> -1
    }

    val headerButtons = remember(headerActions, requesters, focusState) {
        headerActions.map { action ->
            HeaderButtonConfig(
                text = action.text,
                icon = action.icon,
                contentDescription = action.contentDescription,
                focusRequester = requesters.getValue(action.target),
                onFocused = { focusState.onHeaderFocused() },
                onClick = action.onClick,
                visible = action.visible
            )
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .focusGroup()
    ) {
        BrowserHeader(
            title = title,
            focusEnabled = focusEnabled,
            focusRequester = headerEnterRequester,
            buttons = headerButtons,
            onDown = { focusState.restoreGridFocus(focusEnabled) }
        )

        Spacer(Modifier.height(gridSpacing))

        TvFileGrid(
            items = items,
            itemKey = itemKey,
            gridState = gridState,
            focusState = gridFocusState,
            columnCount = columnCount,
            selectedIndex = focusState.selectedIndex,
            selectionVisible = focusState.gridSelectionVisible,
            enabled = focusEnabled,
            isMarked = { itemKey(it) in markedKeys },
            onSelectionChanged = { index, item ->
                focusState.onSelectionChanged(index)
                onItemFocused(item)
            },
            onExitUp = { focusState.onExitUp(headerRequester()) },
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick,
            onItemFocused = { index, item ->
                focusState.onGridFocused(index)
                onItemFocused(item)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // No focusRestorer here on purpose: it hooks onEnter of the group
                    // and would redirect the explicit index requests this screen
                    // relies on. The grid is virtualised anyway, so a saved child is
                    // often gone by the time it would be restored.
                    .focusRequester(gridEnterRequester)
                    .focusRestorer()
                    .focusGroup(),
            itemContent = itemContent
        )
    }

    LaunchedEffect(itemKeys, focusKey, focusRequestKey, columnCount, focusEnabled) {
        focusState.applyInitialFocus(
            targetIndex = requestedFocusIndex(),
            enabled = focusEnabled,
            onItemFocused = { index -> onItemFocused(items[index]) },
            headerRequester = ::headerRequester
        )
    }
}
