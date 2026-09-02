package com.luckycatpaw.luckyfilestv.data.source

/**
 * A starting point of the browser: a mounted volume or a network share the user configured.
 */
data class Volume(val path: SourcePath, val name: String, val kind: VolumeKind)

enum class VolumeKind {
    /** Built-in storage of the device. */
    INTERNAL,

    /** USB stick, SD card and anything else that can be unplugged. */
    REMOVABLE,

    /** Network share, reachable only while the server answers. */
    NETWORK
}
