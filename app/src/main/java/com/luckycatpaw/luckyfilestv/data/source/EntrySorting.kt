package com.luckycatpaw.luckyfilestv.data.source

import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode

/**
 * Ordering of a directory listing.
 *
 * Sorting is pure and identical for every source, so it lives next to the interface instead
 * of being reimplemented per source. Names are compared case insensitively and also serve as
 * the tie breaker, which keeps the order stable when dates or sizes match.
 */
internal fun entryComparator(sort: SortOptions): Comparator<FileEntry> = Comparator { first, second ->
    if (sort.foldersFirst && first.isDirectory != second.isDirectory) {
        return@Comparator if (first.isDirectory) -1 else 1
    }

    val primary = when (sort.mode) {
        FileSortMode.NAME -> compareNames(first, second)
        FileSortMode.DATE -> first.lastModified.compareTo(second.lastModified)
        FileSortMode.SIZE -> first.size.compareTo(second.size)
        FileSortMode.TYPE -> String.CASE_INSENSITIVE_ORDER.compare(first.path.extension, second.path.extension)
    }

    val directed = if (sort.ascending) primary else -primary
    if (directed != 0) directed else compareNames(first, second).let { if (sort.ascending) it else -it }
}

private fun compareNames(first: FileEntry, second: FileEntry): Int =
    String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name)
