package com.luckycatpaw.luckyfilestv.ui.picker.model

import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo

/**
 * Stable identity of everything the picker can display.
 *
 * These strings are compared against [PickerBrowserItem.key] to restore focus after a
 * navigation, so both sides have to agree character for character. Building them in one
 * place is what guarantees that: a caller that needs the key of a location it has not
 * loaded yet (a freshly created folder, the directory it is navigating back out of) uses
 * the same function the item itself uses.
 */
object PickerKeys {

    fun local(path: String): String = "local:$path"

    fun providerRoot(root: DocumentRootInfo): String = "root:${root.authority}:${root.rootId}"

    fun providerDocument(document: ProviderDocumentInfo): String =
        "document:${document.authority}:${document.documentId}"
}

sealed interface PickerBrowserItem {

    val key: String
    val name: String
    val isDirectory: Boolean
    val isFile: Boolean

    data class Local(val item: BrowserItem) : PickerBrowserItem {
        override val key: String
            get() = PickerKeys.local(item.path)

        override val name: String
            get() = item.name

        override val isDirectory: Boolean
            get() = item is BrowserItem.Storage || item is BrowserItem.Folder

        override val isFile: Boolean
            get() = item is BrowserItem.File
    }

    data class ProviderRoot(val root: DocumentRootInfo) : PickerBrowserItem {
        override val key: String
            get() = PickerKeys.providerRoot(root)

        override val name: String
            get() = root.title

        override val isDirectory: Boolean
            get() = true

        override val isFile: Boolean
            get() = false
    }

    data class ProviderDocument(val document: ProviderDocumentInfo, val root: DocumentRootInfo) : PickerBrowserItem {
        override val key: String
            get() = PickerKeys.providerDocument(document)

        override val name: String
            get() = document.displayName

        override val isDirectory: Boolean
            get() = document.isDirectory

        override val isFile: Boolean
            get() = !document.isDirectory
    }
}
