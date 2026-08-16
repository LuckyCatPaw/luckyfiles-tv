package com.luckycatpaw.luckyfilestv.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog

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
    val languageSystemFocus = remember { FocusRequester() }
    val languageEnglishFocus = remember { FocusRequester() }
    val languageGermanFocus = remember { FocusRequester() }
    val hideFolderJpgFocus = remember { FocusRequester() }
    val useFolderJpgFocus = remember { FocusRequester() }
    val optimizeNamesFocus = remember { FocusRequester() }
    val sortNameFocus = remember { FocusRequester() }
    val sortDateFocus = remember { FocusRequester() }
    val sortSizeFocus = remember { FocusRequester() }
    val sortTypeFocus = remember { FocusRequester() }
    val sortDirectionFocus = remember { FocusRequester() }
    val foldersFirstFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    var focusedItem by remember {
        mutableStateOf(SettingsFocusItem.LANGUAGE_SYSTEM)
    }

    fun focusRequesterFor(
        item: SettingsFocusItem
    ): FocusRequester {
        return when (item) {
            SettingsFocusItem.LANGUAGE_SYSTEM -> languageSystemFocus
            SettingsFocusItem.LANGUAGE_ENGLISH -> languageEnglishFocus
            SettingsFocusItem.LANGUAGE_GERMAN -> languageGermanFocus
            SettingsFocusItem.HIDE_FOLDER_JPG -> hideFolderJpgFocus
            SettingsFocusItem.USE_FOLDER_JPG -> useFolderJpgFocus
            SettingsFocusItem.OPTIMIZE_NAMES -> optimizeNamesFocus
            SettingsFocusItem.SORT_NAME -> sortNameFocus
            SettingsFocusItem.SORT_DATE -> sortDateFocus
            SettingsFocusItem.SORT_SIZE -> sortSizeFocus
            SettingsFocusItem.SORT_TYPE -> sortTypeFocus
            SettingsFocusItem.SORT_DIRECTION -> sortDirectionFocus
            SettingsFocusItem.FOLDERS_FIRST -> foldersFirstFocus
            SettingsFocusItem.CLOSE -> closeFocus
        }
    }

    TvModalDialog(
        onDismiss = onDismiss,
        dimAlpha = 0.72f
    ) {
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

            runCatching {
                focusRequesterFor(
                    focusedItem
                ).requestFocus()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    when (
                        event.nativeKeyEvent.keyCode
                    ) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true

                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            focusedItem ==
                                    SettingsFocusItem.LANGUAGE_SYSTEM
                        }

                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            focusedItem ==
                                    SettingsFocusItem.CLOSE
                        }

                        else -> false
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .align(
                        Alignment.Center
                    )
                    .width(
                        780.dp
                    )
                    .fillMaxHeight(
                        0.88f
                    )
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surface,
                        RoundedCornerShape(
                            18.dp
                        )
                    )
                    .border(
                        width = 1.dp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                                .copy(
                                    alpha = 0.45f
                                ),
                        shape =
                            RoundedCornerShape(
                                18.dp
                            )
                    )
                    .padding(
                        horizontal = 28.dp,
                        vertical = 24.dp
                    )
            ) {
                Text(
                    text = stringResource(R.string.settings),
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurface,
                    fontSize = 26.sp,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            6.dp
                        )
                )

                Text(
                    text = stringResource(R.string.settings_subtitle),
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    fontSize = 14.sp
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            22.dp
                        )
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {
                    item(
                        key = "section_language"
                    ) {
                        SettingsSectionTitle(
                            title = stringResource(R.string.settings_section_language)
                        )
                    }

                    item(
                        key = "language_system"
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.language_system),
                            selected = settings.languageTag == null,
                            focusRequester = languageSystemFocus,
                            onFocused = {
                                focusedItem = SettingsFocusItem.LANGUAGE_SYSTEM
                            },
                            onClick = {
                                onLanguageTagChanged(null)
                            }
                        )
                    }

                    item(
                        key = "language_english"
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.language_english),
                            selected = settings.languageTag == "en",
                            focusRequester = languageEnglishFocus,
                            onFocused = {
                                focusedItem = SettingsFocusItem.LANGUAGE_ENGLISH
                            },
                            onClick = {
                                onLanguageTagChanged("en")
                            }
                        )
                    }

                    item(
                        key = "language_german"
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.language_german),
                            selected = settings.languageTag == "de",
                            focusRequester = languageGermanFocus,
                            onFocused = {
                                focusedItem = SettingsFocusItem.LANGUAGE_GERMAN
                            },
                            onClick = {
                                onLanguageTagChanged("de")
                            }
                        )
                    }

                    item(
                        key = "language_space"
                    ) {
                        SettingsSectionSpacer()
                    }

                    item(
                        key = "section_folder"
                    ) {
                        SettingsSectionTitle(
                            title = stringResource(R.string.settings_section_folder)
                        )
                    }

                    item(
                        key = "hide_folder_jpg"
                    ) {
                        SettingsToggleRow(
                            title =
                                stringResource(R.string.settings_hide_folder_jpg),
                            description =
                                stringResource(R.string.settings_hide_folder_jpg_desc),
                            checked =
                                settings.hideFolderJpg,
                            checkedLabel = stringResource(R.string.state_on),
                            uncheckedLabel = stringResource(R.string.state_off),
                            focusRequester =
                                hideFolderJpgFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.HIDE_FOLDER_JPG
                            },
                            onClick = {
                                onHideFolderJpgChanged(
                                    !settings.hideFolderJpg
                                )
                            }
                        )
                    }

                    item(
                        key = "use_folder_jpg"
                    ) {
                        SettingsToggleRow(
                            title =
                                stringResource(R.string.settings_use_folder_jpg),
                            description =
                                stringResource(R.string.settings_use_folder_jpg_desc),
                            checked =
                                settings.useFolderJpgAsIcon,
                            checkedLabel = stringResource(R.string.state_on),
                            uncheckedLabel = stringResource(R.string.state_off),
                            focusRequester =
                                useFolderJpgFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.USE_FOLDER_JPG
                            },
                            onClick = {
                                onUseFolderJpgAsIconChanged(
                                    !settings.useFolderJpgAsIcon
                                )
                            }
                        )
                    }

                    item(
                        key = "folder_space"
                    ) {
                        SettingsSectionSpacer()
                    }

                    item(
                        key = "section_names"
                    ) {
                        SettingsSectionTitle(
                            title = stringResource(R.string.settings_section_filenames)
                        )
                    }

                    item(
                        key = "optimize_names"
                    ) {
                        SettingsToggleRow(
                            title =
                                stringResource(R.string.settings_optimize_names),
                            description =
                                stringResource(R.string.settings_optimize_names_desc),
                            checked =
                                settings.optimizeFileNames,
                            checkedLabel = stringResource(R.string.state_on),
                            uncheckedLabel = stringResource(R.string.state_off),
                            focusRequester =
                                optimizeNamesFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.OPTIMIZE_NAMES
                            },
                            onClick = {
                                onOptimizeFileNamesChanged(
                                    !settings.optimizeFileNames
                                )
                            }
                        )
                    }

                    item(
                        key = "names_space"
                    ) {
                        SettingsSectionSpacer()
                    }

                    item(
                        key = "section_sort"
                    ) {
                        SettingsSectionTitle(
                            title = stringResource(R.string.settings_section_sorting)
                        )
                    }

                    item(
                        key = "sort_description"
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 4.dp,
                                    vertical = 4.dp
                                )
                        ) {
                            Text(
                                text =
                                    stringResource(R.string.settings_sort_by),
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurface,
                                fontSize = 16.sp,
                                fontWeight =
                                    FontWeight.Medium
                            )

                            Spacer(
                                modifier =
                                    Modifier.height(
                                        4.dp
                                    )
                            )

                            Text(
                                text =
                                    stringResource(R.string.settings_sort_by_desc),
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    item(
                        key = "sort_name"
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.sort_name),
                            selected =
                                settings.sortMode ==
                                        FileSortMode.NAME,
                            focusRequester =
                                sortNameFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.SORT_NAME
                            },
                            onClick = {
                                onSortModeChanged(
                                    FileSortMode.NAME
                                )
                            }
                        )
                    }

                    item(
                        key = "sort_date"
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.sort_date),
                            selected =
                                settings.sortMode ==
                                        FileSortMode.DATE,
                            focusRequester =
                                sortDateFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.SORT_DATE
                            },
                            onClick = {
                                onSortModeChanged(
                                    FileSortMode.DATE
                                )
                            }
                        )
                    }

                    item(
                        key = "sort_size"
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.sort_size),
                            selected =
                                settings.sortMode ==
                                        FileSortMode.SIZE,
                            focusRequester =
                                sortSizeFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.SORT_SIZE
                            },
                            onClick = {
                                onSortModeChanged(
                                    FileSortMode.SIZE
                                )
                            }
                        )
                    }

                    item(
                        key = "sort_type"
                    ) {
                        SettingsChoiceRow(
                            title = stringResource(R.string.sort_type),
                            selected =
                                settings.sortMode ==
                                        FileSortMode.TYPE,
                            focusRequester =
                                sortTypeFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.SORT_TYPE
                            },
                            onClick = {
                                onSortModeChanged(
                                    FileSortMode.TYPE
                                )
                            }
                        )
                    }

                    item(
                        key = "sort_gap"
                    ) {
                        Spacer(
                            modifier =
                                Modifier.height(
                                    6.dp
                                )
                        )
                    }

                    item(
                        key = "sort_direction"
                    ) {
                        SettingsToggleRow(
                            title =
                                stringResource(R.string.settings_sort_direction),
                            description =
                                sortDirectionDescription(
                                    mode =
                                        settings.sortMode,
                                    ascending =
                                        settings.sortAscending
                                ),
                            checked =
                                settings.sortAscending,
                            checkedLabel =
                                stringResource(R.string.ascending),
                            uncheckedLabel =
                                stringResource(R.string.descending),
                            focusRequester =
                                sortDirectionFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.SORT_DIRECTION
                            },
                            onClick = {
                                onSortAscendingChanged(
                                    !settings.sortAscending
                                )
                            }
                        )
                    }

                    item(
                        key = "folders_first"
                    ) {
                        SettingsToggleRow(
                            title =
                                stringResource(R.string.folders_first),
                            description =
                                stringResource(R.string.folders_first_desc),
                            checked =
                                settings.foldersFirst,
                            checkedLabel = stringResource(R.string.state_on),
                            uncheckedLabel = stringResource(R.string.state_off),
                            focusRequester =
                                foldersFirstFocus,
                            onFocused = {
                                focusedItem =
                                    SettingsFocusItem.FOLDERS_FIRST
                            },
                            onClick = {
                                onFoldersFirstChanged(
                                    !settings.foldersFirst
                                )
                            }
                        )
                    }

                    item(
                        key = "close_gap"
                    ) {
                        Spacer(
                            modifier =
                                Modifier.height(
                                    14.dp
                                )
                        )
                    }

                    item(
                        key = "close"
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.End
                        ) {
                            SettingsCloseButton(
                                focusRequester =
                                    closeFocus,
                                onFocused = {
                                    focusedItem =
                                        SettingsFocusItem.CLOSE
                                },
                                onClick =
                                    onDismiss
                            )
                        }
                    }

                    item(
                        key = "bottom_gap"
                    ) {
                        Spacer(
                            modifier =
                                Modifier.height(
                                    8.dp
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 4.dp,
                bottom = 6.dp
            )
    ) {
        Text(
            text = title,
            color =
                MaterialTheme
                    .colorScheme
                    .primary,
            fontSize = 19.sp,
            fontWeight =
                FontWeight.SemiBold
        )

        Spacer(
            modifier =
                Modifier.height(
                    5.dp
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    1.dp
                )
                .background(
                    MaterialTheme
                        .colorScheme
                        .primary
                        .copy(
                            alpha = 0.28f
                        )
                )
        )
    }
}

@Composable
private fun SettingsSectionSpacer() {
    Spacer(
        modifier =
            Modifier.height(
                18.dp
            )
    )
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
    var focused by remember {
        mutableStateOf(false)
    }

    val shape =
        RoundedCornerShape(
            12.dp
        )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(
                focusRequester
            )
            .onFocusChanged { state ->
                focused =
                    state.isFocused

                if (state.isFocused) {
                    onFocused()
                }
            }
            .onPreviewKeyEvent { event ->
                handleActivation(
                    event = event,
                    onClick = onClick
                )
            }
            .focusable()
            .background(
                color =
                    if (focused) {
                        MaterialTheme
                            .colorScheme
                            .primaryContainer
                    } else {
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    },
                shape = shape
            )
            .then(
                if (focused) {
                    Modifier.border(
                        width = 2.dp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = 18.dp,
                vertical = 14.dp
            ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Column(
            modifier =
                Modifier.weight(
                    1f
                )
        ) {
            Text(
                text = title,
                color =
                    if (focused) {
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                    } else {
                        MaterialTheme
                            .colorScheme
                            .onSurface
                    },
                fontSize = 16.sp,
                fontWeight =
                    FontWeight.Medium
            )

            Spacer(
                modifier =
                    Modifier.height(
                        4.dp
                    )
            )

            Text(
                text = description,
                color =
                    if (focused) {
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                            .copy(
                                alpha = 0.74f
                            )
                    } else {
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                    },
                fontSize = 13.sp,
                lineHeight = 17.sp
            )
        }

        Spacer(
            modifier =
                Modifier.width(
                    22.dp
                )
        )

        SettingsValueBadge(
            text =
                if (checked) {
                    checkedLabel
                } else {
                    uncheckedLabel
                },
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
    var focused by remember {
        mutableStateOf(false)
    }

    val shape =
        RoundedCornerShape(
            10.dp
        )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(
                focusRequester
            )
            .onFocusChanged { state ->
                focused =
                    state.isFocused

                if (state.isFocused) {
                    onFocused()
                }
            }
            .onPreviewKeyEvent { event ->
                handleActivation(
                    event = event,
                    onClick = onClick
                )
            }
            .focusable()
            .background(
                color =
                    when {
                        focused ->
                            MaterialTheme
                                .colorScheme
                                .primaryContainer

                        selected ->
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant

                        else ->
                            MaterialTheme
                                .colorScheme
                                .surface
                    },
                shape = shape
            )
            .then(
                if (focused) {
                    Modifier.border(
                        width = 2.dp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = 18.dp,
                vertical = 11.dp
            ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier =
                Modifier.weight(
                    1f
                ),
            color =
                if (focused) {
                    MaterialTheme
                        .colorScheme
                        .onPrimaryContainer
                } else {
                    MaterialTheme
                        .colorScheme
                        .onSurface
                },
            fontSize = 15.sp,
            fontWeight =
                if (selected) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                }
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
private fun SettingsValueBadge(
    text: String,
    active: Boolean,
    focused: Boolean
) {
    val backgroundColor =
        when {
            active ->
                MaterialTheme
                    .colorScheme
                    .primary

            else ->
                MaterialTheme
                    .colorScheme
                    .surface
        }

    val contentColor =
        when {
            active ->
                MaterialTheme
                    .colorScheme
                    .onPrimary

            focused ->
                MaterialTheme
                    .colorScheme
                    .onSurface

            else ->
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        }

    Row(
        modifier = Modifier
            .background(
                backgroundColor,
                RoundedCornerShape(
                    8.dp
                )
            )
            .padding(
                horizontal = 12.dp,
                vertical = 6.dp
            ),
        horizontalArrangement =
            Arrangement.Center,
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight =
                FontWeight.Medium
        )
    }
}

@Composable
private fun SettingsCloseButton(
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember {
        mutableStateOf(false)
    }

    val shape =
        RoundedCornerShape(
            10.dp
        )

    Row(
        modifier = Modifier
            .focusRequester(
                focusRequester
            )
            .onFocusChanged { state ->
                focused =
                    state.isFocused

                if (state.isFocused) {
                    onFocused()
                }
            }
            .onPreviewKeyEvent { event ->
                handleActivation(
                    event = event,
                    onClick = onClick
                )
            }
            .focusable()
            .background(
                if (focused) {
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
                } else {
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant
                },
                shape
            )
            .then(
                if (focused) {
                    Modifier.border(
                        width = 2.dp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = 22.dp,
                vertical = 10.dp
            ),
        horizontalArrangement =
            Arrangement.Center,
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.close),
            color =
                if (focused) {
                    MaterialTheme
                        .colorScheme
                        .onPrimaryContainer
                } else {
                    MaterialTheme
                        .colorScheme
                        .onSurface
                },
            fontSize = 14.sp
        )
    }
}

private fun handleActivation(
    event: KeyEvent,
    onClick: () -> Unit
): Boolean {
    val keyCode =
        event.nativeKeyEvent.keyCode

    val activationKey =
        keyCode ==
                AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode ==
                AndroidKeyEvent.KEYCODE_ENTER ||
                keyCode ==
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode ==
                AndroidKeyEvent.KEYCODE_BUTTON_A

    if (!activationKey) {
        return false
    }

    if (
        event.type ==
        KeyEventType.KeyUp
    ) {
        onClick()
    }

    return true
}

@Composable
private fun sortDirectionDescription(
    mode: FileSortMode,
    ascending: Boolean
): String {
    return when (mode) {
        FileSortMode.NAME -> {
            if (ascending) {
                stringResource(R.string.sort_name_ascending_desc)
            } else {
                stringResource(R.string.sort_name_descending_desc)
            }
        }

        FileSortMode.DATE -> {
            if (ascending) {
                stringResource(R.string.sort_date_ascending_desc)
            } else {
                stringResource(R.string.sort_date_descending_desc)
            }
        }

        FileSortMode.SIZE -> {
            if (ascending) {
                stringResource(R.string.sort_size_ascending_desc)
            } else {
                stringResource(R.string.sort_size_descending_desc)
            }
        }

        FileSortMode.TYPE -> {
            if (ascending) {
                stringResource(R.string.sort_type_ascending_desc)
            } else {
                stringResource(R.string.sort_type_descending_desc)
            }
        }
    }
}
