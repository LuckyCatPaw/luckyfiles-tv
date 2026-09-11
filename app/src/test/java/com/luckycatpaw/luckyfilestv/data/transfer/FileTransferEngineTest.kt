package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import android.content.ContextWrapper
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.source.DirectoryListing
import com.luckycatpaw.luckyfilestv.data.source.FileSource
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real writes and replacement journals, with the network side held in memory.
 *
 * The context only supplies the journal directory. Android's notification and resource
 * stubs are not involved in the successful writes or the read failure exercised here.
 */
class FileTransferEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val remoteSource = FakeRemoteFileSource()
    private val registry = FileSourceRegistry(listOf(remoteSource))

    @Before
    fun onlyOnPosix() {
        assumeTrue(File.separatorChar == '/')
    }

    @Test
    fun `an unreadable subtree cannot replace an existing local folder`() = runTest {
        val target = temporaryFolder.newFolder("target")
        val original = File(target, "original.txt").apply { writeText("keep") }
        remoteSource
            .directory("smb://nas/media/folder")
            .file("smb://nas/media/folder/copied.txt", content = "new")
            .directory("smb://nas/media/folder/locked")
            .file("smb://nas/media/folder/locked/missing.txt", content = "not copied")
        remoteSource.unlistable += "smb://nas/media/folder/locked"

        assertFailsWith<FileTreeReadException> {
            engine().copy(
                request = CopyRequest(
                    source = remote("smb://nas/media/folder"),
                    target = TransferTarget.Local(target),
                    replace = true,
                    totalBytes = 3L
                ),
                onBytesCopied = { }
            )
        }

        assertEquals("keep", original.readText())
        assertEquals(listOf("original.txt"), target.listFiles()?.map { it.name })
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".luckyfiles-") })
        assertTrue(remoteSource.deleted.isEmpty())
    }

    @Test
    fun `a complete copy still replaces the old local target`() = runTest {
        val target = File(temporaryFolder.root, "target.txt").apply { writeText("old") }
        remoteSource.file("smb://nas/media/source.txt", content = "new")

        val result = engine().copy(
            request = CopyRequest(
                source = remote("smb://nas/media/source.txt"),
                target = TransferTarget.Local(target),
                replace = true,
                totalBytes = 3L
            ),
            onBytesCopied = { }
        )

        assertEquals("new", target.readText())
        assertTrue(result.unreadableDirectories.isEmpty())
        assertFalse(result.cleanupWarning)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".luckyfiles-") })
    }

    @Test
    fun `moving a remote folder copies hidden contents before deleting the source`() = runTest {
        val location = SourcePath.parse("smb://nas/media/folder")
        remoteSource
            .directory(location.value)
            .file("${location.value}/.nomedia", content = "a")
            .directory("${location.value}/.config")
            .file("${location.value}/.config/settings", content = "bb")
            .file("${location.value}/folder.jpg", content = "ccc")
        val target = File(temporaryFolder.root, "target")
        val tally = TransferTally()
        val source = remote(location.value)
        val size = source.scan().size

        val runner = TransferRunner(
            engine = engine(),
            messages = StubTransferMessages,
            freeSpace = FreeSpaceCheck(
                messages = StubTransferMessages,
                availableBytes = { Long.MAX_VALUE },
                totalBytes = { Long.MAX_VALUE }
            ),
            targetFor = { TransferTarget.Local(it.toFile()) },
            describeFailure = { it.message.orEmpty() }
        )

        runner.run(
            request = RunRequest(
                items = listOf(PlannedTransfer(source, SourcePath.of(target), replace = false, size = size)),
                operation = TransferOperation.MOVE,
                totalBytes = size,
                targetDirectory = null
            ),
            tally = tally,
            onProgress = { }
        )

        assertEquals("a", File(target, ".nomedia").readText())
        assertEquals("bb", File(target, ".config/settings").readText())
        assertEquals("ccc", File(target, "folder.jpg").readText())
        assertEquals(location.value, remoteSource.deleted.last())
        assertFalse(source.exists())
        assertTrue(tally.result(cancelled = false).issues.isEmpty())
    }

    @Test
    fun `replacing a symbolic link preserves the unrelated file it points to`() = runTest {
        val referent = File(temporaryFolder.newFolder("elsewhere"), "keep.txt").apply { writeText("keep") }
        val target = File(temporaryFolder.root, "target.txt")
        assumeTrue(runCatching { Files.createSymbolicLink(target.toPath(), referent.toPath()) }.isSuccess)
        remoteSource.file("smb://nas/media/source.txt", content = "new")

        engine().copy(
            request = CopyRequest(
                source = remote("smb://nas/media/source.txt"),
                target = TransferTarget.Local(target),
                replace = true,
                totalBytes = 3L
            ),
            onBytesCopied = { }
        )

        assertEquals("keep", referent.readText())
        assertEquals("new", target.readText())
        assertFalse(Files.isSymbolicLink(target.toPath()))
    }

    @Test
    fun `moving onto a link to the source leaves the transferred file at the destination`() = runTest {
        val original = File(temporaryFolder.newFolder("source"), "film.mkv").apply { writeText("film") }
        val target = File(temporaryFolder.newFolder("target"), "film.mkv")
        assumeTrue(runCatching { Files.createSymbolicLink(target.toPath(), original.toPath()) }.isSuccess)
        val source = TransferSource.Local(original, FileTreeWalker())
        val tally = TransferTally()

        runner(engine()).run(
            RunRequest(
                listOf(PlannedTransfer(source, SourcePath.of(target), replace = true, size = 4L)),
                TransferOperation.MOVE,
                totalBytes = 4L,
                targetDirectory = null
            ),
            tally,
            onProgress = { }
        )

        assertFalse(original.exists())
        assertFalse(Files.isSymbolicLink(target.toPath()))
        assertEquals("film", target.readText())
        assertEquals(0, tally.result(cancelled = false).sourceDeleteWarningCount)
        assertTrue(tally.result(cancelled = false).issues.isEmpty())
    }

    @Test
    fun `the engine refuses remote self replacement through host and share case aliases`() = runTest {
        val source = remote("smb://NAS/Media/film.mkv")
        remoteSource.file(source.pathValue, content = "keep")
        val target = TransferTarget.Remote(SourcePath.parse("smb://nas/media/film.mkv"), registry)

        assertFailsWith<IOException> {
            engine().copy(
                request = CopyRequest(
                    source = source,
                    target = target,
                    replace = true,
                    totalBytes = 4L
                ),
                onBytesCopied = { }
            )
        }

        assertEquals("keep", remoteSource.openInput(source.location).use { it.readBytes().decodeToString() })
        assertTrue(remoteSource.deleted.isEmpty())
        assertTrue(remoteSource.written.isEmpty())
    }

    @Test
    fun `a copying move keeps a remote file added after its directory was listed`() = runTest {
        val location = "smb://nas/media/folder"
        remoteSource.directory(location).file("$location/copied.txt", content = "copied")
        val target = File(temporaryFolder.root, "target")
        val tally = TransferTally()

        runner(engine()).run(
            RunRequest(
                listOf(PlannedTransfer(remote(location), SourcePath.of(target), replace = false, size = 6L)),
                TransferOperation.MOVE,
                totalBytes = 6L,
                targetDirectory = null
            ),
            tally,
            onProgress = { progress ->
                if (progress.bytesProcessed > 0L) remoteSource.file("$location/new.txt", content = "keep")
            }
        )

        assertEquals("copied", File(target, "copied.txt").readText())
        assertFalse(File(target, "new.txt").exists())
        assertTrue(remote("$location/new.txt").exists())
        assertTrue(remote(location).exists())
        assertEquals(1, tally.result(cancelled = false).sourceDeleteWarningCount)
    }

    @Test
    fun `a changed local source is kept after its copy has completed`() = runTest {
        val original = File(temporaryFolder.root, "source.txt").apply { writeText("old") }
        val source = TransferSource.Local(original, FileTreeWalker())
        val target = File(temporaryFolder.root, "target.txt")
        val engine = engine()
        val copied = engine.copy(
            request = CopyRequest(
                source = source,
                target = TransferTarget.Local(target),
                replace = false,
                totalBytes = 3L,
                trackSource = true
            ),
            onBytesCopied = { }
        )
        original.writeText("new contents")

        assertFailsWith<IOException> { engine.delete(source, copied.copiedEntries) }

        assertEquals("new contents", original.readText())
        assertEquals("old", target.readText())
    }

    @Test
    fun `a new local child is never included in source cleanup`() = runTest {
        val original = temporaryFolder.newFolder("source")
        File(original, "copied.txt").writeText("old")
        val source = TransferSource.Local(original, FileTreeWalker())
        val target = File(temporaryFolder.root, "target")
        val engine = engine()
        val copied = engine.copy(
            request = CopyRequest(
                source = source,
                target = TransferTarget.Local(target),
                replace = false,
                totalBytes = 3L,
                trackSource = true
            ),
            onBytesCopied = { }
        )
        val added = File(original, "new.txt").apply { writeText("keep") }

        assertFailsWith<IOException> { engine.delete(source, copied.copiedEntries) }

        assertEquals("keep", added.readText())
        assertEquals("old", File(target, "copied.txt").readText())
        assertFalse(File(target, "new.txt").exists())
    }

    @Test
    fun `a remote file changed during copying is kept and its partial target is removed`() = runTest {
        val location = "smb://nas/media/source.txt"
        remoteSource.file(location, content = "old")
        val target = File(temporaryFolder.root, "target.txt")

        assertFailsWith<IOException> {
            engine().copy(
                request = CopyRequest(
                    source = remote(location),
                    target = TransferTarget.Local(target),
                    replace = false,
                    totalBytes = 3L,
                    trackSource = true
                ),
                onBytesCopied = { copied ->
                    if (copied > 0L) remoteSource.file(location, content = "new contents")
                }
            )
        }

        assertTrue(remote(location).exists())
        assertTrue(remoteSource.deleted.isEmpty())
        assertFalse(target.exists())
    }

    @Test
    fun `a replaced local entry is kept even when its size and timestamp match`() = runTest {
        val original = File(temporaryFolder.root, "source.txt").apply { writeText("old") }
        val source = TransferSource.Local(original, FileTreeWalker())
        assumeTrue(source.readState("")?.identity != null)
        val target = File(temporaryFolder.root, "target.txt")
        val engine = engine()
        val copied = engine.copy(
            request = CopyRequest(
                source = source,
                target = TransferTarget.Local(target),
                replace = false,
                totalBytes = 3L,
                trackSource = true
            ),
            onBytesCopied = { }
        )
        val timestamp = Files.getLastModifiedTime(original.toPath())
        Files.move(original.toPath(), File(temporaryFolder.root, "old-entry.txt").toPath())
        original.writeText("new")
        Files.setLastModifiedTime(original.toPath(), timestamp)

        assertFailsWith<IOException> { engine.delete(source, copied.copiedEntries) }

        assertEquals("new", original.readText())
        assertEquals("old", target.readText())
    }

    @Test
    fun `a remote replacement cannot delete the ancestor containing its source`() = runTest {
        val targetPath = SourcePath.parse("smb://nas/media/A")
        val sourcePath = targetPath.child("A")
        remoteSource.directory(targetPath.value).directory(sourcePath.value)
            .file("${sourcePath.value}/keep.txt", content = "keep")

        assertFailsWith<IOException> {
            engine().copy(
                CopyRequest(remote(sourcePath.value), TransferTarget.Remote(targetPath, registry), true, 4L),
                onBytesCopied = { }
            )
        }

        assertTrue(remoteSource.deleted.isEmpty())
        assertTrue(remoteSource.written.isEmpty())
        val content = remoteSource.openInput(sourcePath.child("keep.txt")).use { it.readBytes().decodeToString() }
        assertEquals("keep", content)
    }

    @Test
    fun `a remote move uses listings and keeps fresh checks after copying and before deletion`() = runTest {
        val location = "smb://nas/media/folder"
        remoteSource.directory(location).directory("$location/sub")
            .file("$location/.hidden", content = "a")
            .file("$location/sub/file.txt", content = "bb")
        val target = File(temporaryFolder.root, "target")
        val source = remote(location)
        val engine = engine()

        val copied = engine.copy(
            CopyRequest(source, TransferTarget.Local(target), false, 3L, trackSource = true),
            onBytesCopied = { }
        )
        engine.delete(source, copied.copiedEntries)

        assertEquals("a", File(target, ".hidden").readText())
        assertEquals("bb", File(target, "sub/file.txt").readText())
        assertEquals(2, remoteSource.statRequests.count { it == "$location/.hidden" })
        assertEquals(2, remoteSource.statRequests.count { it == "$location/sub/file.txt" })
        assertEquals(listOf(location, "$location/sub", location, "$location/sub"), remoteSource.listRequests)
        assertEquals(location, remoteSource.deleted.last())
    }

    @Test
    fun `an edit between listing and copying is detected with reused listing metadata`() = runTest {
        val location = SourcePath.parse("smb://nas/media/folder")
        remoteSource.directory(location.value).file("${location.value}/file.txt", content = "old")
        val changingSource = object : FileSource by remoteSource {
            override suspend fun list(path: SourcePath, options: ListOptions): DirectoryListing {
                val listing = remoteSource.list(path, options)
                remoteSource.file("${location.value}/file.txt", content = "changed")
                return listing
            }
        }
        val sources = FileSourceRegistry(listOf(changingSource))
        val source = TransferSource.Remote(location, sources)
        val target = File(temporaryFolder.root, "target")

        assertFailsWith<IOException> {
            engine(sources).copy(
                CopyRequest(source, TransferTarget.Local(target), false, 3L, trackSource = true),
                onBytesCopied = { }
            )
        }

        assertFalse(target.exists())
        assertTrue(remoteSource.deleted.isEmpty())
        val content = remoteSource.openInput(location.child("file.txt")).use { it.readBytes().decodeToString() }
        assertEquals("changed", content)
    }

    @Test
    fun `recovery restores a backup link without moving or deleting its referent`() = runTest {
        val parent = temporaryFolder.newFolder("target")
        val referent = File(temporaryFolder.newFolder("elsewhere"), "keep.txt").apply { writeText("keep") }
        val target = File(parent, "film.mkv")
        val backup = File(parent, ".luckyfiles-test.backup")
        assumeTrue(runCatching { Files.createSymbolicLink(backup.toPath(), referent.toPath()) }.isSuccess)
        val prepared = File(parent, ".luckyfiles-prepared").apply { writeText("unfinished") }
        val journalRoot = temporaryFolder.newFolder("journals")
        val journalDirectory = File(journalRoot, "replacement_transactions").apply { mkdir() }
        val journal = File(journalDirectory, "test.txn")

        // State after moving the old link aside but before installing the new file. The
        // journal format is unchanged, so pending replacements survive an app update.
        DataOutputStream(journal.outputStream()).use { output ->
            output.writeInt(1)
            output.writeUTF(target.absolutePath)
            output.writeUTF(prepared.absolutePath)
            output.writeUTF(backup.absolutePath)
        }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir(): File = journalRoot
        }

        ReplacementTransactionStore(context, FileTreeWalker()).recoverPending()

        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertEquals(referent.toPath(), Files.readSymbolicLink(target.toPath()))
        assertEquals("keep", referent.readText())
        assertFalse(prepared.exists())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
    }

    private fun runner(engine: TransferEngine) = TransferRunner(
        engine = engine,
        messages = StubTransferMessages,
        freeSpace = FreeSpaceCheck(
            messages = StubTransferMessages,
            availableBytes = { Long.MAX_VALUE },
            totalBytes = { Long.MAX_VALUE }
        ),
        targetFor = { TransferTarget.Local(it.toFile()) },
        describeFailure = { it.message.orEmpty() }
    )

    private fun remote(path: String) = TransferSource.Remote(SourcePath.parse(path), registry)

    private fun engine(sources: FileSourceRegistry = registry): FileTransferEngine {
        val journalRoot = temporaryFolder.newFolder("journals")
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir(): File = journalRoot
        }

        return FileTransferEngine(context, FileTreeWalker(), sources, messages = StubTransferMessages)
    }
}
