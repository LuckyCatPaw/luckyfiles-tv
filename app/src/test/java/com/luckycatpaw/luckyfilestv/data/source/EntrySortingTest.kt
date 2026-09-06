package com.luckycatpaw.luckyfilestv.data.source

import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import kotlin.test.assertEquals
import org.junit.Test

class EntrySortingTest {

    @Test
    fun `folders come before files whatever their names are`() {
        val sorted = sortedBy(SortOptions(mode = FileSortMode.NAME, ascending = true, foldersFirst = true))

        assertEquals(listOf("apples", "Zebra", "Apple.txt", "banana.txt"), sorted)
    }

    @Test
    fun `descending turns the names around but not the folders`() {
        val sorted = sortedBy(SortOptions(mode = FileSortMode.NAME, ascending = false, foldersFirst = true))

        assertEquals(listOf("Zebra", "apples", "banana.txt", "Apple.txt"), sorted)
    }

    @Test
    fun `without foldersFirst everything is one list`() {
        val sorted = sortedBy(SortOptions(mode = FileSortMode.NAME, ascending = true, foldersFirst = false))

        // Case insensitive throughout, so Apple and apples sit next to each other.
        assertEquals(listOf("Apple.txt", "apples", "banana.txt", "Zebra"), sorted)
    }

    @Test
    fun `date sorts on the timestamp`() {
        val entries = listOf(
            entry("late.txt", lastModified = 300L),
            entry("early.txt", lastModified = 100L),
            entry("middle.txt", lastModified = 200L)
        )

        val sorted = entries.sortedWith(entryComparator(SortOptions(mode = FileSortMode.DATE, foldersFirst = false)))

        assertEquals(listOf("early.txt", "middle.txt", "late.txt"), sorted.map { it.name })
    }

    @Test
    fun `size sorts on the byte count`() {
        val entries = listOf(entry("small.txt", size = 10L), entry("big.txt", size = 30L), entry("mid.txt", size = 20L))
        val options = SortOptions(mode = FileSortMode.SIZE, ascending = false, foldersFirst = false)

        val sorted = entries.sortedWith(entryComparator(options))

        assertEquals(listOf("big.txt", "mid.txt", "small.txt"), sorted.map { it.name })
    }

    @Test
    fun `type sorts on the extension`() {
        val entries = listOf(entry("clip.mkv"), entry("movie.avi"), entry("note.txt"))
        val options = SortOptions(mode = FileSortMode.TYPE, foldersFirst = false)

        val sorted = entries.sortedWith(entryComparator(options))

        assertEquals(listOf("movie.avi", "clip.mkv", "note.txt"), sorted.map { it.name })
    }

    @Test
    fun `the name settles a tie and follows the direction`() {
        // Without this the order of two equally sized files would depend on what the
        // filesystem happened to list first, and the grid would reshuffle on every visit.
        val entries = listOf(entry("b.txt", size = 5L), entry("a.txt", size = 5L))

        val ascending = entries
            .sortedWith(entryComparator(SortOptions(mode = FileSortMode.SIZE, foldersFirst = false)))
        val descending = entries.sortedWith(
            entryComparator(SortOptions(mode = FileSortMode.SIZE, ascending = false, foldersFirst = false))
        )

        assertEquals(listOf("a.txt", "b.txt"), ascending.map { it.name })
        assertEquals(listOf("b.txt", "a.txt"), descending.map { it.name })
    }

    private fun sortedBy(options: SortOptions): List<String> = listOf(
        entry("banana.txt"),
        entry("Zebra", isDirectory = true),
        entry("Apple.txt"),
        entry("apples", isDirectory = true)
    ).sortedWith(entryComparator(options)).map { it.name }

    private fun entry(name: String, isDirectory: Boolean = false, size: Long = 0L, lastModified: Long = 0L): FileEntry =
        FileEntry(
            path = SourcePath.parse("/storage/$name"),
            name = name,
            isDirectory = isDirectory,
            size = size,
            lastModified = lastModified
        )
}
