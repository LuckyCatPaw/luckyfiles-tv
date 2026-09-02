package com.luckycatpaw.luckyfilestv.data.common.model

import com.luckycatpaw.luckyfilestv.data.source.Volume

sealed interface BrowserItem {

    val name: String
    val path: String

    /**
     * A volume tile on the storage overview.
     *
     * Carries the [Volume] itself instead of copies of its fields, so a screen can tell a
     * system volume from a share the user added without asking the data layer again.
     */
    data class Storage(val volume: Volume) : BrowserItem {
        override val name: String get() = volume.name
        override val path: String get() = volume.path.value
    }

    data class Folder(override val name: String, override val path: String) : BrowserItem

    data class File(override val name: String, override val path: String) : BrowserItem
}
