package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import java.io.IOException

/** Metadata captured before an entry is copied, and checked again before it is removed. */
internal data class TransferEntryState(
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val size: Long,
    val modified: String,
    val identity: String? = null
) {

    /** Deleting our own children changes a directory's timestamp and size. */
    fun matches(current: TransferEntryState?): Boolean = if (isDirectory) {
        current != null && current.isDirectory && !current.isSymbolicLink && identity == current.identity
    } else {
        this == current
    }
}

/** Only entries whose output was closed successfully are eligible for source cleanup. */
internal data class CopiedEntry(val relativePath: String, val state: TransferEntryState)

/** Use the listing snapshot remotely; local lstat also supplies link type and entry identity. */
internal suspend fun TransferSource.captureEntry(entry: TransferEntry): CopiedEntry {
    val state = entry.listedState ?: readState(entry.relativePath)
        ?: throw IOException("Source disappeared: $pathValue/${entry.relativePath}")
    if (state.isSymbolicLink || state.isDirectory != (entry.type == FileTreeEntryType.DIRECTORY)) {
        throw IOException("Source changed during transfer: $pathValue/${entry.relativePath}")
    }
    return CopiedEntry(entry.relativePath, state)
}

/**
 * Removes what this transfer copied, without enumerating another tree for deletion.
 *
 * Metadata checks catch observable edits and local entry replacements. They are not a
 * lock against concurrent writers: a server may expose no file identity, and equal size
 * and timestamps cannot prove equal contents. Empty-only directory removal is the final
 * guard for new children, including ones created after the metadata checks.
 */
internal suspend fun TransferSource.deleteCopied(entries: List<CopiedEntry>) {
    if (entries.none { it.relativePath.isEmpty() }) throw IOException("No copied source entries: $pathValue")

    // Check the whole recorded set before deleting anything, then check each entry again
    // immediately before its deletion. A changed file keeps its original and the copy.
    // Remote sources use one fresh listing per parent for the preflight: this retains
    // fail-fast behaviour without a second stat for every child. A single entry only
    // needs the final check, which already covers the whole set.
    if (entries.size > 1) verifyCopiedEntries(entries)
    for (entry in entries.asReversed()) {
        requireUnchanged(entry)
        deleteEntry(entry.relativePath, entry.state.isDirectory)
    }
}

internal suspend fun TransferSource.requireUnchanged(entry: CopiedEntry) {
    entry.requireUnchanged(readState(entry.relativePath), pathValue)
}

internal fun CopiedEntry.requireUnchanged(current: TransferEntryState?, sourcePath: String) {
    if (!state.matches(current)) {
        throw IOException("Source changed during transfer: $sourcePath/$relativePath")
    }
}
