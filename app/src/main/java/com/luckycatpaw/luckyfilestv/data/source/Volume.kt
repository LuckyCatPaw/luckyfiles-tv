package com.luckycatpaw.luckyfilestv.data.source

/**
 * A starting point of the browser: a mounted volume or a network share the user configured.
 */
data class Volume(
    val path: SourcePath,
    val name: String,
    val kind: VolumeKind,
    /**
     * Key of the configuration this volume came from, `null` for volumes the system mounts.
     *
     * Carries two things at once, and deliberately so: it says that the user created this
     * volume and can therefore edit and remove it, and it says which entry to act on. A
     * separate flag would only repeat what the presence of a key already tells.
     */
    val configId: String? = null
) {

    /** `true` when the volume owns a menu, which system volumes never do. */
    val isUserManaged: Boolean
        get() = configId != null
}

enum class VolumeKind {
    /** Built-in storage of the device. */
    INTERNAL,

    /** USB stick, SD card and anything else that can be unplugged. */
    REMOVABLE,

    /** Network share, reachable only while the server answers. */
    NETWORK
}
