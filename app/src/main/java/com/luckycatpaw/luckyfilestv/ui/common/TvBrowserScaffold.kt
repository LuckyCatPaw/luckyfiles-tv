package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
            rememberLazyGridState(
                initialFirstVisibleItemIndex = initialGridIndex,
                initialFirstVisibleItemScrollOffset = initialGridOffset
            )
        }

    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    val safeSpace = TvFileGridDefaults.SafeHorizontalSpace
    val availableWidth = configuration.screenWidthDp.dp - safeSpace * 2
    val columnCount = TvFileGridDefaults.columnCount(availableWidth)

    // Key focus state by stable keys. A repository refresh may return a new list
    // instance holding the same entries and must not reset focus.
    val itemKeys = remember(items) { items.map(itemKey) }
    val gridFocusState = rememberTvFileGridFocusState(itemKeys)

    val requesters =
        remember {
            HeaderFocusTarget.entries.associateWith { FocusRequester() }
        }

    // Resolve an explicit return target before the new list is first rendered.
    // This prevents a one-frame highlight on a stale index during back navigation.
    val explicitFocusIndex = focusKey?.let(itemKeys::indexOf) ?: -1
    val initialFocusIndex =
        when {
            explicitFocusIndex >= 0 -> explicitFocusIndex
            initialGridPosition != null && items.isNotEmpty() -> initialGridIndex
            else -> -1
        }

    val focusState =
        rememberBrowserFocusState<String, HeaderFocusTarget>(
            keys = itemKeys,
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
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset
                )
            )
        }
    }

    /**
     * Focus requester for [target], falling back to the first visible button.
     * Visibility comes from [headerActions], so it is stated exactly once.
     */
    fun requesterFor(target: HeaderFocusTarget?): FocusRequester? {
        if (!focusEnabled) return null

        val visibleActions = headerActions.filter { it.visible }
        val match = target?.let { wanted -> visibleActions.firstOrNull { it.target == wanted } }

        return (match ?: visibleActions.firstOrNull())?.let { requesters.getValue(it.target) }
    }

    fun requestedFocusIndex(): Int {
        val requested = focusKey?.let(itemKeys::indexOf) ?: -1

        return when {
            requested >= 0 -> requested
            focusKey != null -> -1
            focusState.selectedIndex in items.indices -> focusState.selectedIndex
            focusState.focusedIndex in items.indices -> focusState.focusedIndex
            items.isNotEmpty() -> 0
            else -> -1
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
    ) {
        BrowserHeader(
            title = title,
            focusEnabled = focusEnabled,
            buttons =
                headerActions.map { action ->
                    HeaderButtonConfig(
                        text = action.text,
                        icon = action.icon,
                        contentDescription = action.contentDescription,
                        focusRequester = requesters.getValue(action.target),
                        onFocused = { focusState.onHeaderFocused(action.target) },
                        onClick = action.onClick,
                        visible = action.visible
                    )
                },
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
            onExitUp = { focusState.onExitUp(requesterFor(focusState.lastHeaderTarget)) },
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick,
            onItemFocused = { index, item ->
                focusState.onGridFocused(index)
                onItemFocused(item)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            itemContent = itemContent
        )
    }

    LaunchedEffect(itemKeys, focusKey, focusRequestKey, columnCount, focusEnabled) {
        focusState.applyInitialFocus(
            targetIndex = requestedFocusIndex(),
            enabled = focusEnabled,
            onItemFocused = { index -> onItemFocused(items[index]) },
            headerRequester = ::requesterFor
        )
    }
}
