package com.luckycatpaw.luckyfilestv.data.common

import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeCycleException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntry
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeOutsideRootException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTreeWalkerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val walker = FileTreeWalker()

    @Test
    fun `scan counts files folders and bytes`() = runTest {
        val stats = walker.scan(sampleTree())

        assertEquals(2L, stats.fileCount)
        // The root itself is not one of its own directories.
        assertEquals(1L, stats.directoryCount)
        assertEquals(8L, stats.size)
        assertEquals(0L, stats.symbolicLinkCount)
        assertEquals(0L, stats.unreadableDirectoryCount)
    }

    @Test
    fun `walk reports every entry with a path relative to the root`() = runTest {
        val entries = walker.entriesOf(sampleTree())

        assertEquals(
            setOf("", "a.txt", "sub", "sub${File.separator}b.bin"),
            entries.map { it.relativePath }.toSet()
        )
    }

    @Test
    fun `walk reports a symbolic link without following it`() = runTest {
        val root = sampleTree()
        val link = File(root, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), File(root, "sub").toPath()) }.isSuccess)

        val entries = walker.entriesOf(root)
        val linkEntry = entries.single { it.relativePath == "link" }

        assertEquals(FileTreeEntryType.SYMBOLIC_LINK, linkEntry.type)
        assertTrue(entries.none { it.relativePath.startsWith("link" + File.separator) })
    }

    @Test
    fun `a symbolic link is counted but contributes no size`() = runTest {
        val root = sampleTree()
        val link = File(root, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), File(root, "sub").toPath()) }.isSuccess)

        val stats = walker.scan(root)

        assertEquals(1L, stats.symbolicLinkCount)
        assertEquals(3L, stats.fileCount)
        assertEquals(8L, stats.size)
    }

    @Test
    fun `walk completes a directory only after its children`() = runTest {
        val log = mutableListOf<String>()

        walker.walk(
            root = sampleTree(),
            onEntry = { log += "enter ${it.relativePath}" },
            onDirectoryComplete = { log += "leave ${it.relativePath}" }
        )

        assertTrue(log.indexOf("enter sub${File.separator}b.bin") < log.indexOf("leave sub"))
        assertEquals("leave ", log.last())
    }

    @Test
    fun `walk hands an unreadable directory to the caller instead of failing`() = runTest {
        withUnreadableSub { root, sub ->
            val unreadable = mutableListOf<File>()
            walker.walk(root = root, onEntry = {}, onUnreadableDirectory = { unreadable += it })

            assertEquals(listOf(sub.canonicalFile), unreadable)
        }
    }

    @Test
    fun `walk fails on an unreadable directory when the caller passes no handler`() = runTest {
        withUnreadableSub { root, sub ->
            val failure = assertFailsWith<FileTreeReadException> { walker.walk(root = root, onEntry = {}) }

            assertEquals(sub.canonicalFile, failure.directory)
        }
    }

    @Test
    fun `scan counts an unreadable directory instead of failing`() = runTest {
        withUnreadableSub { root, _ ->
            val stats = walker.scan(root)

            assertEquals(1L, stats.unreadableDirectoryCount)
            // The directory itself was reached, only its contents were not.
            assertEquals(1L, stats.directoryCount)
            assertEquals(1L, stats.fileCount)
        }
    }

    @Test
    fun `walk stops at the next entry once its coroutine is cancelled`() = runTest {
        val root = sampleTree()
        val entries = mutableListOf<String>()

        val job = launch {
            walker.walk(
                root = root,
                onEntry = {
                    entries += it.relativePath
                    this@launch.cancel()
                }
            )
        }
        job.join()

        assertTrue(job.isCancelled)
        // The check sits at the top of the loop, so the root got through and nothing else.
        assertEquals(listOf(""), entries)
    }

    @Test
    fun `delete reports a file it could not remove`() = runTest {
        val root = sampleTree()
        val sub = File(root, "sub")

        try {
            // A file is removed by writing to its directory, so a read only directory is
            // what makes the entry inside it undeletable.
            assumeTrue(sub.setWritable(false) && !File(sub, "b.bin").delete())

            assertFailsWith<IOException> { walker.delete(root) }
        } finally {
            sub.setWritable(true)
        }
    }

    @Test
    fun `delete removes a symbolic link and not what it points at`() = runTest {
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "keep.txt").writeText("keep")
        val root = sampleTree()
        val link = File(root, "link").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess)

        walker.delete(root)

        assertFalse(root.exists())
        assertTrue(File(outside, "keep.txt").exists())
    }

    @Test
    fun `walk fails when the root does not exist`() = runTest {
        assertFailsWith<FileNotFoundException> {
            walker.walk(root = File(temporaryFolder.root, "missing"), onEntry = {})
        }
    }

    @Test
    fun `scan of a root that is a symbolic link reports the link and stops`() = runTest {
        val stats = walker.scan(linkTo(sampleTree()))

        // TransferCoordinator refuses an item on exactly this signal. Resolving the root
        // here would leave that refusal silently unreachable.
        assertEquals(1L, stats.symbolicLinkCount)
        assertEquals(1L, stats.fileCount)
        assertEquals(0L, stats.size)
        assertEquals(0L, stats.directoryCount)
    }

    // The two guards below cannot fire while the tree holds still, because a link is
    // recognised before the descent and never followed. They exist for the window between
    // that check and the realpath, which the injected resolver stands in for here.
    @Test
    fun `a directory that resolves outside the root aborts the walk`() = runTest {
        val root = sampleTree()
        val swapped = FileTreeWalker { if (it.name == "sub") File("/elsewhere/sub") else it.canonicalFile }

        assertFailsWith<FileTreeOutsideRootException> { swapped.entriesOf(root) }
    }

    @Test
    fun `a directory that resolves to one already seen aborts the walk`() = runTest {
        val root = sampleTree()
        val swapped = FileTreeWalker { if (it.name == "sub") root.canonicalFile else it.canonicalFile }

        assertFailsWith<FileTreeCycleException> { swapped.entriesOf(root) }
    }

    @Test
    fun `delete removes the whole tree including the root`() = runTest {
        val root = sampleTree()

        walker.delete(root)

        assertFalse(root.exists())
    }

    /** `root/a.txt` (3 bytes) and `root/sub/b.bin` (5 bytes). */
    private fun sampleTree(): File {
        val root = temporaryFolder.newFolder("root")
        File(root, "a.txt").writeText("abc")
        val sub = File(root, "sub")
        assertTrue(sub.mkdir())
        File(sub, "b.bin").writeText("12345")
        return root
    }

    /**
     * Runs [block] on a tree whose `sub` directory cannot be listed, and skips the test where
     * the platform will not produce one — Windows ignores [File.setReadable] on directories.
     */
    private suspend fun withUnreadableSub(block: suspend (root: File, sub: File) -> Unit) {
        val root = sampleTree()
        val sub = File(root, "sub")

        try {
            assumeTrue(sub.setReadable(false) && sub.listFiles() == null)

            block(root, sub)
        } finally {
            sub.setReadable(true)
        }
    }

    /** A symbolic link next to [target], or a skipped test where the platform refuses one. */
    private fun linkTo(target: File): File {
        val link = File(temporaryFolder.root, "link-to-${target.name}")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess)
        return link
    }

    private suspend fun FileTreeWalker.entriesOf(root: File): List<FileTreeEntry> {
        val entries = mutableListOf<FileTreeEntry>()
        walk(root = root, onEntry = { entries += it })
        return entries
    }
}
