package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeEntryType
import com.luckycatpaw.luckyfilestv.data.source.DirectoryListing
import com.luckycatpaw.luckyfilestv.data.source.FileEntry
import com.luckycatpaw.luckyfilestv.data.source.FileSource
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val remoteSource = FakeRemoteFileSource()

    private val registry = FileSourceRegistry(listOf(remoteSource))

    // Local

    @Test
    fun `a local tree is walked with paths relative to its root`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val root = localTree()

        val entries = local(root).collect()

        assertEquals(setOf("", "a.txt", "sub", "sub/b.bin"), entries.map { it.relativePath }.toSet())
    }

    @Test
    fun `a local entry opens the bytes it stands for`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val root = localTree()

        val entry = local(root).collect().single { it.relativePath == "a.txt" }

        assertEquals("abc", entry.openInput().use { it.readBytes() }.decodeToString())
    }

    @Test
    fun `a local scan reports the size and the links it will not follow`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val root = localTree()
        val link = File(root, "link").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(link, File(root, "sub").toPath()) }.isSuccess)

        val scan = local(root).scan()

        assertEquals(8L, scan.size)
        assertEquals(1L, scan.symbolicLinkCount)
    }

    @Test
    fun `a local source answers for what it is`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val root = localTree()

        val source = local(root)

        assertTrue(source.isLocal)
        assertTrue(source.exists())
        assertTrue(source.isDirectory())
        assertFalse(source.isSymbolicLink())
        assertEquals(root.name, source.name)
        assertEquals(root.absolutePath, source.pathValue)
    }

    @Test
    fun `a local source removes its tree after a move`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val root = localTree()

        local(root).delete()

        assertFalse(root.exists())
    }

    // Remote

    @Test
    fun `a remote file is one entry with an empty relative path`() = runTest {
        remoteSource.file("smb://nas/media/Film.mkv", content = "film", lastModified = 5L)

        val entries = remote("smb://nas/media/Film.mkv").collect()

        assertEquals(1, entries.size)
        assertEquals("", entries.single().relativePath)
        assertEquals(FileTreeEntryType.FILE, entries.single().type)
        assertEquals(5L, entries.single().lastModified)
    }

    @Test
    fun `a remote tree is walked depth first and completed afterwards`() = runTest {
        remoteTree()
        val log = mutableListOf<String>()

        remote("smb://nas/media").walk(
            onEntry = { log += "enter ${it.relativePath}" },
            onDirectoryComplete = { log += "leave ${it.relativePath}" },
            onUnreadableDirectory = { log += "unreadable $it" }
        )

        assertTrue(log.indexOf("enter Season 1/b.mkv") < log.indexOf("leave Season 1"))
        assertEquals("leave ", log.last())
    }

    @Test
    fun `a remote entry opens the bytes it stands for`() = runTest {
        remoteTree()

        val entry = remote("smb://nas/media").collect().single { it.relativePath == "a.mkv" }

        assertEquals("aa", entry.openInput().use { it.readBytes() }.decodeToString())
    }

    @Test
    fun `a remote scan adds up the files and knows of no links`() = runTest {
        remoteTree()

        val scan = remote("smb://nas/media").scan()

        assertEquals(5L, scan.size)
        // A share reports plain files and directories, nothing that points elsewhere.
        assertEquals(0L, scan.symbolicLinkCount)
    }

    @Test
    fun `a directory that will not answer is reported and the walk carries on`() = runTest {
        remoteTree()
        remoteSource.unlistable += "smb://nas/media/Season 1"
        val unreadable = mutableListOf<String>()
        val entries = mutableListOf<String>()

        remote("smb://nas/media").walk(
            onEntry = { entries += it.relativePath },
            onDirectoryComplete = { },
            onUnreadableDirectory = { unreadable += it }
        )

        assertEquals(listOf("smb://nas/media/Season 1"), unreadable)
        assertTrue(entries.contains("a.mkv"))
    }

    @Test
    fun `a share that leads back into itself is stopped rather than followed forever`() = runTest {
        // No canonical path exists here to recognise a loop by, so depth is what ends it: a
        // DFS referral pointing at its own parent would otherwise fill the target disk.
        remoteSource.directory("smb://nas/media")
        remoteSource.endlessChildName = "loop"
        val unreadable = mutableListOf<String>()

        remote("smb://nas/media").walk(
            onEntry = { },
            onDirectoryComplete = { },
            onUnreadableDirectory = { unreadable += it }
        )

        assertEquals(1, unreadable.size)
        assertTrue(unreadable.single().endsWith("/loop"))
    }

    @Test
    fun `stopping the transfer is not an unreadable directory`() = runTest {
        // Without the rethrow the abort is reported as a folder that could not be read and
        // the walk moves on to the next sibling.
        remoteTree()
        val cancelling = FileSourceRegistry(listOf(CancellingSource()))

        assertFailsWith<CancellationException> {
            TransferSource.Remote(SourcePath.parse("smb://nas/media"), cancelling)
                .walk(onEntry = { }, onDirectoryComplete = { }, onUnreadableDirectory = { })
        }
    }

    @Test
    fun `a remote source answers for what it is`() = runTest {
        remoteTree()

        val source = remote("smb://nas/media")

        assertFalse(source.isLocal)
        assertTrue(source.exists())
        assertTrue(source.isDirectory())
        assertFalse(source.isSymbolicLink())
        assertEquals("media", source.name)
        assertEquals("smb://nas/media", source.pathValue)
        assertFalse(remote("smb://nas/media/gone").exists())
    }

    private fun local(root: File) = TransferSource.Local(root, FileTreeWalker())

    private fun remote(path: String) = TransferSource.Remote(SourcePath.parse(path), registry)

    private suspend fun TransferSource.collect(): List<TransferEntry> {
        val entries = mutableListOf<TransferEntry>()
        walk(onEntry = { entries += it }, onDirectoryComplete = { }, onUnreadableDirectory = { })
        return entries
    }

    /** `root/a.txt` (3 bytes) and `root/sub/b.bin` (5 bytes). */
    private fun localTree(): File {
        val root = temporaryFolder.newFolder("root")
        File(root, "a.txt").writeText("abc")
        val sub = File(root, "sub").apply { mkdir() }
        File(sub, "b.bin").writeText("12345")
        return root
    }

    /** `media/a.mkv` (2 bytes) and `media/Season 1/b.mkv` (3 bytes). */
    private fun remoteTree() {
        remoteSource
            .directory("smb://nas/media", lastModified = 1L)
            .file("smb://nas/media/a.mkv", content = "aa", lastModified = 2L)
            .directory("smb://nas/media/Season 1", lastModified = 3L)
            .file("smb://nas/media/Season 1/b.mkv", content = "bbb", lastModified = 4L)
    }

    /** Answers a listing the way a cancelled transfer does. */
    private class CancellingSource : FileSource by FakeRemoteFileSource() {
        override suspend fun stat(path: SourcePath) = FileEntry(
            path = path,
            name = path.name,
            isDirectory = true,
            size = 0L,
            lastModified = 0L
        )

        override suspend fun list(path: SourcePath, options: ListOptions): DirectoryListing =
            throw CancellationException("stopped")
    }
}
