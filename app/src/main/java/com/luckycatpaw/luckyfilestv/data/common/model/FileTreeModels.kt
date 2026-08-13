package com.luckycatpaw.luckyfilestv.data.common.model

import java.io.File
import java.io.IOException

enum class FileTreeEntryType {
    FILE,
    DIRECTORY,
    SYMBOLIC_LINK
}

data class FileTreeEntry(
    val file: File,
    val relativePath: String,
    val type: FileTreeEntryType,
    val size: Long
)

data class FileTreeStats(
    val size: Long,
    val fileCount: Long,
    val directoryCount: Long,
    val symbolicLinkCount: Long
)

class FileTreeReadException(
    val directory: File
) : IOException("Directory could not be read: ${directory.absolutePath}")

class FileTreeCycleException(
    val directory: File
) : IOException("Directory cycle detected: ${directory.absolutePath}")

class FileTreeOutsideRootException(
    val file: File
) : IOException("Entry resolves outside the source tree: ${file.absolutePath}")
