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

    /**
     * A file tile.
     *
     * [size] and [lastModified] come from the listing that produced this item and are
     * carried purely so that something identifying the *content* is available without a
     * second request. On a share that matters: stat-ing a file to build a thumbnail key
     * would be one round trip per tile, while the directory response that named the file
     * already contained both values.
     *
     * `0` means the listing did not supply them — a local search result, for instance,
     * where reading them per hit would cost more than it saves and the file system can be
     * asked directly anyway.
     */
    data class File(
        override val name: String,
        override val path: String,
        val size: Long = 0L,
        val lastModified: Long = 0L
    ) : BrowserItem
}
