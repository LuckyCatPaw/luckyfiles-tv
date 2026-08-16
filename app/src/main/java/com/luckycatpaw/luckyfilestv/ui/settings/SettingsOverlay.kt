package com.luckycatpaw.luckyfilestv.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.ui.common.DialogCard
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog
import com.luckycatpaw.luckyfilestv.ui.common.tvContentColor
import com.luckycatpaw.luckyfilestv.ui.common.tvFocusHighlight
import com.luckycatpaw.luckyfilestv.ui.common.tvFocusable

private enum class SettingsFocusItem {
    LANGUAGE_SYSTEM,
    LANGUAGE_ENGLISH,
    LANGUAGE_GERMAN,
    HIDE_FOLDER_JPG,
    USE_FOLDER_JPG,
    OPTIMIZE_NAMES,
    SORT_NAME,
    SORT_DATE,
    SORT_SIZE,
    SORT_TYPE,
    SORT_DIRECTION,
    FOLDERS_FIRST,
    CLOSE
}

@Composable
fun SettingsOverlay(
    settings: FileManagerSettings,
    onLanguageTagChanged: (String?) -> Unit,
    onHideFolderJpgChanged: (Boolean) -> Unit,
    onUseFolderJpgAsIconChanged: (Boolean) -> Unit,
    onOptimizeFileNamesChanged: (Boolean) -> Unit,
    onSortModeChanged: (FileSortMode) -> Unit,
    onSortAscendingChanged: (Boolean) -> Unit,
    onFoldersFirstChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val requesters = remember {
        SettingsFocusItem.entries.associateWith { FocusRequester() }
    }
    var focusedItem by remember { mutableStateOf(SettingsFocusItem.LANGUAGE_SYSTEM) }

    val onLabel = stringResource(R.string.state_on)
    val offLabel = stringResource(R.string.state_off)

    // LazyListScope is not a composable scope, so every string has to be resolved here.
    val labels = SettingsTexts(
        sectionLanguage = stringResource(R.string.settings_section_language),
        sectionFolder = stringResource(R.string.settings_section_folder),
        sectionFileNames = stringResource(R.string.settings_section_filenames),
        sectionSorting = stringResource(R.string.settings_section_sorting),
        languageSystem = stringResource(R.string.language_system),
        languageEnglish = stringResource(R.string.language_english),
        languageGerman = stringResource(R.string.language_german),
        hideFolderJpg = stringResource(R.string.settings_hide_folder_jpg),
        hideFolderJpgDesc = stringResource(R.string.settings_hide_folder_jpg_desc),
        useFolderJpg = stringResource(R.string.settings_use_folder_jpg),
        useFolderJpgDesc = stringResource(R.string.settings_use_folder_jpg_desc),
        optimizeNames = stringResource(R.string.settings_optimize_names),
        optimizeNamesDesc = stringResource(R.string.settings_optimize_names_desc),
        sortBy = stringResource(R.string.settings_sort_by),
        sortByDesc = stringResource(R.string.settings_sort_by_desc),
        sortName = stringResource(R.string.sort_name),
        sortDate = stringResource(R.string.sort_date),
        sortSize = stringResource(R.string.sort_size),
        sortType = stringResource(R.string.sort_type),
        sortDirection = stringResource(R.string.settings_sort_direction),
        sortDirectionDesc = sortDirectionDescription(settings.sortMode, settings.sortAscending),
        ascending = stringResource(R.string.ascending),
        descending = stringResource(R.string.descending),
        foldersFirst = stringResource(R.string.folders_first),
        foldersFirstDesc = stringResource(R.string.folders_first_desc)
    )

    TvModalDialog(onDismiss = onDismiss, dimAlpha = 0.72f) {
        // Restore focus after setting changes. The dialog window prevents focus from
        // escaping to the browser if the requester is briefly unavailable.
        LaunchedEffect(
            settings.languageTag,
            settings.hideFolderJpg,
            settings.useFolderJpgAsIcon,
            settings.optimizeFileNames,
            settings.sortMode,
            settings.sortAscending,
            settings.foldersFirst
        ) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { requesters.getValue(focusedItem).requestFocus() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true

                        AndroidKeyEvent.KEYCODE_DPAD_UP ->
                            focusedItem == SettingsFocusItem.LANGUAGE_SYSTEM

                        AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
                            focusedItem == SettingsFocusItem.CLOSE

                        else -> false
                    }
                }
        ) {
            DialogCard(
                width = 780.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(0.88f),
                padding = 24.dp,
                borderAlpha = 0.45f
            ) {
                Text(
                    text = stringResource(R.string.settings),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(22.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    section("language", labels.sectionLanguage)

                    choice(
                        key = "language_system",
                        title = labels.languageSystem,
                        selected = settings.languageTag == null,
                        item = SettingsFocusItem.LANGUAGE_SYSTEM,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onLanguageTagChanged(null) }
                    )
                    choice(
                        key = "language_english",
                        title = labels.languageEnglish,
                        selected = settings.languageTag == "en",
                        item = SettingsFocusItem.LANGUAGE_ENGLISH,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onLanguageTagChanged("en") }
                    )
                    choice(
                        key = "language_german",
                        title = labels.languageGerman,
                        selected = settings.languageTag == "de",
                        item = SettingsFocusItem.LANGUAGE_GERMAN,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onLanguageTagChanged("de") }
                    )

                    gap("language_space", 18.dp)
                    section("folder", labels.sectionFolder)

                    toggle(
                        key = "hide_folder_jpg",
                        title = labels.hideFolderJpg,
                        description = labels.hideFolderJpgDesc,
                        checked = settings.hideFolderJpg,
                        checkedLabel = onLabel,
                        uncheckedLabel = offLabel,
                        item = SettingsFocusItem.HIDE_FOLDER_JPG,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onHideFolderJpgChanged(!settings.hideFolderJpg) }
                    )
                    toggle(
                        key = "use_folder_jpg",
                        title = labels.useFolderJpg,
                        description = labels.useFolderJpgDesc,
                        checked = settings.useFolderJpgAsIcon,
                        checkedLabel = onLabel,
                        uncheckedLabel = offLabel,
                        item = SettingsFocusItem.USE_FOLDER_JPG,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onUseFolderJpgAsIconChanged(!settings.useFolderJpgAsIcon) }
                    )

                    gap("folder_space", 18.dp)
                    section("names", labels.sectionFileNames)

                    toggle(
                        key = "optimize_names",
                        title = labels.optimizeNames,
                        description = labels.optimizeNamesDesc,
                        checked = settings.optimizeFileNames,
                        checkedLabel = onLabel,
                        uncheckedLabel = offLabel,
                        item = SettingsFocusItem.OPTIMIZE_NAMES,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onOptimizeFileNamesChanged(!settings.optimizeFileNames) }
                    )

                    gap("names_space", 18.dp)
                    section("sort", labels.sectionSorting)

                    item(key = "sort_description") {
                        SettingsDescription(title = labels.sortBy, description = labels.sortByDesc)
                    }

                    val sortChoices = listOf(
                        Triple(FileSortMode.NAME, "sort_name", labels.sortName),
                        Triple(FileSortMode.DATE, "sort_date", labels.sortDate),
                        Triple(FileSortMode.SIZE, "sort_size", labels.sortSize),
                        Triple(FileSortMode.TYPE, "sort_type", labels.sortType)
                    )

                    sortChoices.forEach { (mode, key, title) ->
                        choice(
                            key = key,
                            title = title,
                            selected = settings.sortMode == mode,
                            item = SortFocusItems.getValue(mode),
                            requesters = requesters,
                            onFocused = { focusedItem = it },
                            onClick = { onSortModeChanged(mode) }
                        )
                    }

                    gap("sort_gap", 6.dp)

                    toggle(
                        key = "sort_direction",
                        title = labels.sortDirection,
                        description = labels.sortDirectionDesc,
                        checked = settings.sortAscending,
                        checkedLabel = labels.ascending,
                        uncheckedLabel = labels.descending,
                        item = SettingsFocusItem.SORT_DIRECTION,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onSortAscendingChanged(!settings.sortAscending) }
                    )
                    toggle(
                        key = "folders_first",
                        title = labels.foldersFirst,
                        description = labels.foldersFirstDesc,
                        checked = settings.foldersFirst,
                        checkedLabel = onLabel,
                        uncheckedLabel = offLabel,
                        item = SettingsFocusItem.FOLDERS_FIRST,
                        requesters = requesters,
                        onFocused = { focusedItem = it },
                        onClick = { onFoldersFirstChanged(!settings.foldersFirst) }
                    )

                    gap("close_gap", 14.dp)

                    item(key = "close") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            SettingsCloseButton(
                                focusRequester = requesters.getValue(SettingsFocusItem.CLOSE),
                                onFocused = { focusedItem = SettingsFocusItem.CLOSE },
                                onClick = onDismiss
                            )
                        }
                    }

                    gap("bottom_gap", 8.dp)
                }
            }
        }
    }
}

private val SortFocusItems = mapOf(
    FileSortMode.NAME to SettingsFocusItem.SORT_NAME,
    FileSortMode.DATE to SettingsFocusItem.SORT_DATE,
    FileSortMode.SIZE to SettingsFocusItem.SORT_SIZE,
    FileSortMode.TYPE to SettingsFocusItem.SORT_TYPE
)

private class SettingsTexts(
    val sectionLanguage: String,
    val sectionFolder: String,
    val sectionFileNames: String,
    val sectionSorting: String,
    val languageSystem: String,
    val languageEnglish: String,
    val languageGerman: String,
    val hideFolderJpg: String,
    val hideFolderJpgDesc: String,
    val useFolderJpg: String,
    val useFolderJpgDesc: String,
    val optimizeNames: String,
    val optimizeNamesDesc: String,
    val sortBy: String,
    val sortByDesc: String,
    val sortName: String,
    val sortDate: String,
    val sortSize: String,
    val sortType: String,
    val sortDirection: String,
    val sortDirectionDesc: String,
    val ascending: String,
    val descending: String,
    val foldersFirst: String,
    val foldersFirstDesc: String
)

private fun LazyListScope.gap(key: String, height: Dp) {
    item(key = key) { Spacer(Modifier.height(height)) }
}

private fun LazyListScope.section(key: String, title: String) {
    item(key = "section_$key") { SettingsSectionTitle(title) }
}

private fun LazyListScope.choice(
    key: String,
    title: String,
    selected: Boolean,
    item: SettingsFocusItem,
    requesters: Map<SettingsFocusItem, FocusRequester>,
    onFocused: (SettingsFocusItem) -> Unit,
    onClick: () -> Unit
) {
    item(key = key) {
        SettingsChoiceRow(
            title = title,
            selected = selected,
            focusRequester = requesters.getValue(item),
            onFocused = { onFocused(item) },
            onClick = onClick
        )
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.toggle(
    key: String,
    title: String,
    description: String,
    checked: Boolean,
    checkedLabel: String,
    uncheckedLabel: String,
    item: SettingsFocusItem,
    requesters: Map<SettingsFocusItem, FocusRequester>,
    onFocused: (SettingsFocusItem) -> Unit,
    onClick: () -> Unit
) {
    item(key = key) {
        SettingsToggleRow(
            title = title,
            description = description,
            checked = checked,
            checkedLabel = checkedLabel,
            uncheckedLabel = uncheckedLabel,
            focusRequester = requesters.getValue(item),
            onFocused = { onFocused(item) },
            onClick = onClick
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
        )
    }
}

@Composable
private fun SettingsDescription(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    checkedLabel: String,
    uncheckedLabel: String,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(
                onClick = onClick,
                focusRequester = focusRequester,
                onFocusChanged = { isFocused ->
                    focused = isFocused
                    if (isFocused) onFocused()
                }
            )
            .tvFocusHighlight(focused, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = tvContentColor(focused),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                color = if (focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 13.sp,
                lineHeight = 17.sp
            )
        }

        Spacer(Modifier.width(22.dp))

        SettingsValueBadge(
            text = if (checked) checkedLabel else uncheckedLabel,
            active = checked,
            focused = focused
        )
    }
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(
                onClick = onClick,
                focusRequester = focusRequester,
                onFocusChanged = { isFocused ->
                    focused = isFocused
                    if (isFocused) onFocused()
                }
            )
            .tvFocusHighlight(
                focused = focused,
                shape = shape,
                unfocusedContainer = if (selected) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = tvContentColor(focused),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )

        if (selected) {
            SettingsValueBadge(
                text = stringResource(R.string.active),
                active = true,
                focused = focused
            )
        }
    }
}

@Composable
private fun SettingsValueBadge(text: String, active: Boolean, focused: Boolean) {
    val backgroundColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }

    val contentColor = when {
        active -> MaterialTheme.colorScheme.onPrimary
        focused -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SettingsCloseButton(focusRequester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .tvFocusable(
                onClick = onClick,
                focusRequester = focusRequester,
                onFocusChanged = { isFocused ->
                    focused = isFocused
                    if (isFocused) onFocused()
                }
            )
            .tvFocusHighlight(focused, shape)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.close),
            color = tvContentColor(focused),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun sortDirectionDescription(mode: FileSortMode, ascending: Boolean): String {
    val resId = when (mode) {
        FileSortMode.NAME ->
            if (ascending) R.string.sort_name_ascending_desc else R.string.sort_name_descending_desc

        FileSortMode.DATE ->
            if (ascending) R.string.sort_date_ascending_desc else R.string.sort_date_descending_desc

        FileSortMode.SIZE ->
            if (ascending) R.string.sort_size_ascending_desc else R.string.sort_size_descending_desc

        FileSortMode.TYPE ->
            if (ascending) R.string.sort_type_ascending_desc else R.string.sort_type_descending_desc
    }
    return stringResource(resId)
}
