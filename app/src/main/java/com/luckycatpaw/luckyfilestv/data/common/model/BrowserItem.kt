package com.luckycatpaw.luckyfilestv.data.common.model

sealed interface BrowserItem {

    val name: String
    val path: String

    data class Storage(override val name: String, override val path: String, val removable: Boolean) : BrowserItem

    data class Folder(override val name: String, override val path: String) : BrowserItem

    data class File(override val name: String, override val path: String) : BrowserItem
}
