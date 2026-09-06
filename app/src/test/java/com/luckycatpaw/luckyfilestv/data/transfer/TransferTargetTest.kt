package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferTargetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val remoteSource = FakeRemoteFileSource()

    private val registry = FileSourceRegistry(listOf(remoteSource))

    // Local

    @Test
    fun `a local target writes what it is given`() = runTest {
        val target = TransferTarget.Local(File(temporaryFolder.root, "Film.mkv"))

        target.openOutput("").use { it.write("film".toByteArray()) }

        assertEquals("film", File(temporaryFolder.root, "Film.mkv").readText())
        assertTrue(target.isLocal)
    }

    @Test
    fun `a local target never overwrites unnoticed`() = runTest {
        // The engine plans conflicts before it writes; a file appearing underneath it has to
        // stop the copy rather than take the old one with it.
        val file = File(temporaryFolder.root, "Film.mkv").apply { writeText("existing") }
        val target = TransferTarget.Local(file)

        assertFailsWith<FileAlreadyExistsException> { target.openOutput("") }

        assertEquals("existing", file.readText())
    }

    @Test
    fun `a local target resolves below its root`() = runTest {
        val root = temporaryFolder.newFolder("target")
        val target = TransferTarget.Local(root)

        target.createDirectory("Season 1")
        target.openOutput("Season 1/b.mkv").use { it.write("bbb".toByteArray()) }

        assertTrue(target.exists("Season 1"))
        assertEquals("bbb", File(root, "Season 1/b.mkv").readText())
        assertFalse(target.exists("Season 2"))
    }

    @Test
    fun `a local target counts a dangling link as something already there`() = runTest {
        // Files.exists would call the name free and the exclusive create would then fail
        // halfway through a transfer instead of at the planning stage.
        val root = temporaryFolder.newFolder("target")
        val link = File(root, "Film.mkv").toPath()
        assumeTrue(
            runCatching { Files.createSymbolicLink(link, File(root, "missing").toPath()) }.isSuccess
        )

        assertTrue(TransferTarget.Local(root).exists("Film.mkv"))
    }

    @Test
    fun `a local target sets the date it was given`() = runTest {
        val file = File(temporaryFolder.root, "Film.mkv")
        val target = TransferTarget.Local(file)
        target.openOutput("").use { it.write("film".toByteArray()) }

        target.setLastModified("", DATE)

        assertEquals(DATE, file.lastModified())
    }

    @Test
    fun `a local target removes what it created`() = runTest {
        val root = temporaryFolder.newFolder("target")
        val target = TransferTarget.Local(root)
        target.createDirectory("Season 1")
        target.openOutput("Season 1/b.mkv").use { it.write("bbb".toByteArray()) }

        target.deleteTree()

        assertFalse(root.exists())
    }

    // Remote

    @Test
    fun `a remote target writes through the source`() = runTest {
        remoteSource.directory("smb://nas/media")
        val target = remote("smb://nas/media/Film.mkv")

        target.openOutput("").use { it.write("film".toByteArray()) }

        assertEquals("film", remoteSource.written.getValue("smb://nas/media/Film.mkv").decodeToString())
        assertFalse(target.isLocal)
    }

    @Test
    fun `a remote target never overwrites unnoticed either`() = runTest {
        remoteSource.file("smb://nas/media/Film.mkv", content = "existing")

        assertFailsWith<SourceException.AlreadyExists> { remote("smb://nas/media/Film.mkv").openOutput("") }
    }

    @Test
    fun `a remote target creates a directory under its parent`() = runTest {
        remoteSource.directory("smb://nas/media")

        remote("smb://nas/media/Season 1").createDirectory("")

        assertEquals(listOf("smb://nas/media/Season 1"), remoteSource.created)
    }

    @Test
    fun `a remote target resolves a relative path segment by segment`() = runTest {
        remoteSource.directory("smb://nas/media")
        val target = remote("smb://nas/media")

        target.openOutput("Season 1/b.mkv").use { it.write("bbb".toByteArray()) }

        assertTrue(remoteSource.written.containsKey("smb://nas/media/Season 1/b.mkv"))
        assertTrue(target.exists("Season 1/b.mkv"))
        assertFalse(target.exists("Season 2/b.mkv"))
    }

    @Test
    fun `a remote target names itself after its location`() = runTest {
        remoteSource.directory("smb://nas/media")

        val target = remote("smb://nas/media")

        assertEquals("media", target.name)
        assertEquals("smb://nas/media", target.pathValue)
        assertTrue(target.exists())
    }

    @Test
    fun `a remote target does what a share cannot without failing`() = runTest {
        // Setting a write time needs a call this does not make, and a server decides for
        // itself when it commits. Both are no-ops rather than errors, because a wrong date
        // is better than a failed copy.
        val target = remote("smb://nas/media/Film.mkv")

        target.setLastModified("", DATE)
        target.flush()
    }

    @Test
    fun `cleaning up after a failed transfer does not fail in turn`() = runTest {
        // deleteTree runs on the way out of an error; a share that has already gone away
        // must not replace the original failure with its own.
        remote("smb://nas/media/gone").deleteTree()

        assertTrue(remoteSource.deleted.isEmpty())
    }

    private fun remote(path: String) = TransferTarget.Remote(SourcePath.parse(path), registry)

    private companion object {
        /** Whole seconds: several filesystems store no more than that. */
        const val DATE = 1_600_000_000_000L
    }
}
