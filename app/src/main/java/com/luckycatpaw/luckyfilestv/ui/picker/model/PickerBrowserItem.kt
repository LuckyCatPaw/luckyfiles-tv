package com.luckycatpaw.luckyfilestv.ui.picker.model

import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo

sealed interface PickerBrowserItem {

    val key: String
    val name: String
    val isDirectory: Boolean
    val isFile: Boolean

    data class Local(val item: BrowserItem) : PickerBrowserItem {
        override val key: String
            get() = "local:${item.path}"

        override val name: String
            get() = item.name

        override val isDirectory: Boolean
            get() = item is BrowserItem.Storage || item is BrowserItem.Folder

        override val isFile: Boolean
            get() = item is BrowserItem.File
    }

    data class ProviderRoot(val root: DocumentRootInfo) : PickerBrowserItem {
        override val key: String
            get() = "root:${root.authority}:${root.rootId}"

        override val name: String
            get() = root.title

        override val isDirectory: Boolean
            get() = true

        override val isFile: Boolean
            get() = false
    }

    data class ProviderDocument(val document: ProviderDocumentInfo, val root: DocumentRootInfo) : PickerBrowserItem {
        override val key: String
            get() = "document:${document.authority}:${document.documentId}"

        override val name: String
            get() = document.displayName

        override val isDirectory: Boolean
            get() = document.isDirectory

        override val isFile: Boolean
            get() = !document.isDirectory
    }
}
