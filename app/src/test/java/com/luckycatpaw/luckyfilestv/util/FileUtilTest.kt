package com.luckycatpaw.luckyfilestv.util

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileUtilTest {

    @Test
    fun `validateFileName trims and returns what is left`() {
        assertEquals("Film.mkv", FileUtil.validateFileName("  Film.mkv  "))
        assertEquals("Season 1", FileUtil.validateFileName("Season 1"))
    }

    @Test
    fun `validateFileName refuses a name with nothing in it`() {
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName("") }
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName("   ") }
    }

    @Test
    fun `validateFileName refuses the two directory entries every folder already has`() {
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName(".") }
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName("..") }
        // Trimmed first, so surrounding space does not smuggle one past.
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName("  ..  ") }
    }

    @Test
    fun `validateFileName refuses anything that would leave the directory`() {
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName("a/b") }
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName("a\\b") }
        assertFailsWith<IllegalArgumentException> { FileUtil.validateFileName("a\u0000b") }
    }

    @Test
    fun `sanitizeFileName replaces separators and drops null bytes`() {
        assertEquals("a_b", FileUtil.sanitizeFileName("a/b"))
        assertEquals("a_b", FileUtil.sanitizeFileName("a\\b"))
        assertEquals("ab", FileUtil.sanitizeFileName("a\u0000b"))
        // Replaced before trimming, so the space around the input still goes.
        assertEquals("a_b", FileUtil.sanitizeFileName("  a/b  "))
    }

    @Test
    fun `sanitizeFileName falls back when nothing usable is left`() {
        assertEquals(FileUtil.UNNAMED, FileUtil.sanitizeFileName(""))
        assertEquals(FileUtil.UNNAMED, FileUtil.sanitizeFileName("   "))
        assertEquals(FileUtil.UNNAMED, FileUtil.sanitizeFileName("."))
        assertEquals(FileUtil.UNNAMED, FileUtil.sanitizeFileName(".."))
        // Reduced to a reserved name by the replacements rather than handed over as one.
        assertEquals(FileUtil.UNNAMED, FileUtil.sanitizeFileName(".\u0000"))
    }

    @Test
    fun `isSafRestrictedPath finds the two folders SAF keeps to itself`() {
        assertTrue(FileUtil.isSafRestrictedPath("/storage/emulated/0/Android/data/com.example"))
        assertTrue(FileUtil.isSafRestrictedPath("/storage/emulated/0/Android/obb/com.example"))
        // The check is on a pair of segments, so the case of either one does not matter.
        assertTrue(FileUtil.isSafRestrictedPath("/storage/emulated/0/android/DATA"))
        assertTrue(FileUtil.isSafRestrictedPath("\\storage\\emulated\\0\\Android\\data"))
    }

    @Test
    fun `isSafRestrictedPath leaves everything else alone`() {
        // Android on its own, with nothing restricted under it.
        assertFalse(FileUtil.isSafRestrictedPath("/storage/emulated/0/Android"))
        // The same two names in the wrong order.
        assertFalse(FileUtil.isSafRestrictedPath("/storage/emulated/0/data/Android"))
        // A folder that merely starts with the word.
        assertFalse(FileUtil.isSafRestrictedPath("/storage/emulated/0/Androidx/data"))
        assertFalse(FileUtil.isSafRestrictedPath("/storage/emulated/0/Movies"))
    }

    @Test
    fun `isSameOrChildPath does not fall for a shared name prefix`() {
        val movies = path("storage", "Movies")

        assertTrue(FileUtil.isSameOrChildPath(movies, movies))
        assertTrue(FileUtil.isSameOrChildPath(movies, path("storage", "Movies", "2024", "Film.mkv")))
        assertFalse(FileUtil.isSameOrChildPath(movies, path("storage", "MoviesOld")))
        assertFalse(FileUtil.isSameOrChildPath(movies, path("storage")))
    }

    @Test
    fun `isSameOrChildPath handles a parent that already ends in a separator`() {
        // The filesystem root is its own separator. Appending another produced a prefix
        // nothing started with, which put every path outside the root.
        assertTrue(FileUtil.isSameOrChildPath(File.separator, path("storage", "Movies")))
        assertTrue(FileUtil.isSameOrChildPath(path("storage") + File.separator, path("storage", "Movies")))
    }

    @Test
    fun `uniqueNameCandidates keeps the extension on the end`() {
        assertEquals(
            listOf("Film.mkv", "Film (1).mkv", "Film (2).mkv"),
            FileUtil.uniqueNameCandidates("Film.mkv", isDirectory = false).take(3).toList()
        )
        // Only the last dot separates the extension.
        assertEquals(
            listOf("Film.2024.mkv", "Film.2024 (1).mkv"),
            FileUtil.uniqueNameCandidates("Film.2024.mkv", isDirectory = false).take(2).toList()
        )
    }

    @Test
    fun `uniqueNameCandidates treats a directory as having no extension`() {
        assertEquals(
            listOf("Season 1.5", "Season 1.5 (1)"),
            FileUtil.uniqueNameCandidates("Season 1.5", isDirectory = true).take(2).toList()
        )
    }

    @Test
    fun `uniqueNameCandidates counts a name that is nothing but a suffix as one piece`() {
        assertEquals(
            listOf(".gitignore", ".gitignore (1)"),
            FileUtil.uniqueNameCandidates(".gitignore", isDirectory = false).take(2).toList()
        )
        // A trailing dot has nothing behind it to be an extension.
        assertEquals(
            listOf("Film.", "Film. (1)"),
            FileUtil.uniqueNameCandidates("Film.", isDirectory = false).take(2).toList()
        )
        assertEquals(
            listOf("README", "README (1)"),
            FileUtil.uniqueNameCandidates("README", isDirectory = false).take(2).toList()
        )
    }

    @Test
    fun `uniqueNameCandidates does not run out`() {
        val candidates = FileUtil.uniqueNameCandidates("Film.mkv", isDirectory = false).take(500).toList()

        assertEquals(500, candidates.size)
        assertEquals("Film (499).mkv", candidates.last())
    }

    @Test
    fun `isHiddenFile hides dot files and folder artwork on request`() {
        assertTrue(FileUtil.isHiddenFile(".nomedia", hideFolderJpg = false))
        assertTrue(FileUtil.isHiddenFile("folder.jpg", hideFolderJpg = true))
        assertTrue(FileUtil.isHiddenFile("FOLDER.JPG", hideFolderJpg = true))
        assertFalse(FileUtil.isHiddenFile("folder.jpg", hideFolderJpg = false))
        assertFalse(FileUtil.isHiddenFile("Film.mkv", hideFolderJpg = true))
    }

    @Test
    fun `runCancellable turns a failure into a result`() = runTest {
        assertEquals("done", FileUtil.runCancellable { "done" }.getOrNull())

        val failed = FileUtil.runCancellable { error("no") }

        assertTrue(failed.isFailure)
    }

    @Test
    fun `runCancellable lets a cancellation through`() = runTest {
        // Swallowing this one would leave the calling coroutine believing it is still alive.
        assertFailsWith<CancellationException> {
            FileUtil.runCancellable { throw CancellationException("cancelled") }
        }
    }

    /** An absolute path in the separator of whatever platform the test is running on. */
    private fun path(vararg segments: String): String = segments.joinToString(File.separator, prefix = File.separator)
}
