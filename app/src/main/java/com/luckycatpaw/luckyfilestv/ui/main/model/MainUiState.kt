package com.luckycatpaw.luckyfilestv.ui.main.model

import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings

internal data class MainUiState(
    val currentPath: String? = null,
    val currentStorageRoot: String? = null,
    val focusedPath: String? = null,
    val focusTargetPath: String? = null,
    val browserItems: List<BrowserItem> = emptyList(),
    val title: String = "",
    val settings: FileManagerSettings = FileManagerSettings(),
    val transferSources: List<BrowserItem> = emptyList(),
    val transferMode: TransferMode? = null,
    val transferProgress: TransferUiProgress? = null,
    val conflictRequest: TransferConflictRequest? = null,
    val transferCompletion: TransferCompletion? = null
)
