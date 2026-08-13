package com.luckycatpaw.luckyfilestv.ui.picker.model

import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings

internal data class PickerUiState(
    val pickerItems: List<PickerBrowserItem> = emptyList(),
    val title: String = "",
    val currentLocalPath: String? = null,
    val currentLocalTitle: String? = null,
    val currentLocalDirectoryWritable: Boolean = false,
    val currentLocalTreeSelectable: Boolean = false,
    val providerStack: List<ProviderLocation> = emptyList(),
    val focusedKey: String? = null,
    val focusTargetKey: String? = null,
    val settings: FileManagerSettings = FileManagerSettings(),
    val providerLoading: Boolean = false,
    val providerInfoMessage: String? = null,
    val providerErrorMessage: String? = null,
    val displayMode: DisplayMode = DisplayMode.BROWSE,
    val currentSearchQuery: String = "",
    val canCreateFolder: Boolean = false,
    val primaryActionLabel: String? = null
)
