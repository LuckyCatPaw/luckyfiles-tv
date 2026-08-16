package com.luckycatpaw.luckyfilestv.ui.picker.model

import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo

internal enum class DisplayMode {
    BROWSE,
    SEARCH,
    RECENTS
}

internal data class ProviderLocation(val root: DocumentRootInfo, val document: ProviderDocumentInfo, val title: String)

internal data class BrowseSnapshot(
    val localPath: String?,
    val storageRoot: String?,
    val providerStack: List<ProviderLocation>,
    val focusKey: String?
)

internal data class RecentEntry(val item: PickerBrowserItem, val modified: Long)
