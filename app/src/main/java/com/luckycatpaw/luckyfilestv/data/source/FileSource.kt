package com.luckycatpaw.luckyfilestv.data.source

import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import java.io.InputStream
import java.io.OutputStream

/**
 * A place files can be browsed and changed in: on-device storage today, a network share
 * later.
 *
 * Implementations are stateless towards the caller and free of Android UI concerns. They
 * signal failures with [SourceException] so that a single mapper produces the user visible
 * text, and they switch to a background dispatcher themselves — no caller has to remember
 * an IO context for a source that turns out to be a network share.
 */
internal interface FileSource {

    /** Scheme this source answers for, see [SourcePath.scheme]. */
    val id: String

    val capabilities: SourceCapabilities

    /**
     * Entry points of this source: mounted volumes locally, configured shares remotely.
     *
     * Must not block on a server. A share that is currently unreachable is still listed and
     * only fails when it is opened.
     */
    suspend fun roots(): List<Volume>

    suspend fun list(path: SourcePath, options: ListOptions): DirectoryListing

    /** Metadata of a single entry, `null` when it does not exist. */
    suspend fun stat(path: SourcePath): FileEntry?

    /** Recursive size and counts, which can take a while on a large tree. */
    suspend fun properties(path: SourcePath): FileProperties

    /** @return the location of the created directory. */
    suspend fun createDirectory(parent: SourcePath, name: String): SourcePath

    /** @return the new location. Never replaces an existing entry. */
    suspend fun rename(path: SourcePath, newName: String): SourcePath

    /** Removes a file, or a directory including its contents. */
    suspend fun delete(path: SourcePath)

    /** Caller closes the stream. [offset] is only honoured with [SourceCapabilities.randomAccessRead]. */
    suspend fun openInput(path: SourcePath, offset: Long = 0L): InputStream

    /** Caller closes the stream. */
    suspend fun openOutput(path: SourcePath, overwrite: Boolean): OutputStream
}

/**
 * What a source can do, so callers stop assuming local file system semantics.
 *
 * @property atomicMove a move never silently replaces existing data and needs no copy.
 * @property cheapMetadata reading sizes, dates and thumbnails costs no round trip, which
 *   decides whether previews may be generated eagerly.
 */
internal data class SourceCapabilities(
    val writable: Boolean,
    val randomAccessRead: Boolean,
    val atomicMove: Boolean,
    val cheapMetadata: Boolean,
    val requiresNetwork: Boolean
)

internal data class FileEntry(
    val path: SourcePath,
    val name: String,
    val isDirectory: Boolean,
    /** `0` for directories and for listings that did not need the size, see [SortOptions.needsSize]. */
    val size: Long,
    val lastModified: Long
)

/**
 * Contents of a directory plus the two facts every caller asked for separately before: how
 * the directory is called and whether it can be written to.
 */
internal data class DirectoryListing(
    val path: SourcePath,
    val displayName: String,
    val writable: Boolean,
    val entries: List<FileEntry>
)

internal data class ListOptions(val sort: SortOptions, val hideFolderJpg: Boolean)

internal data class SortOptions(
    val mode: FileSortMode = FileSortMode.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true
) {
    /** Sizes are one stat per entry locally, so they are only read when they are sorted on. */
    val needsSize: Boolean
        get() = mode == FileSortMode.SIZE
}

internal fun FileManagerSettings.toListOptions(): ListOptions = ListOptions(
    sort = SortOptions(mode = sortMode, ascending = sortAscending, foldersFirst = foldersFirst),
    hideFolderJpg = hideFolderJpg
)
