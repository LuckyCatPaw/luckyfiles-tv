package com.luckycatpaw.luckyfilestv.data.repository

import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.source.DirectoryListing
import com.luckycatpaw.luckyfilestv.data.source.FileEntry
import com.luckycatpaw.luckyfilestv.data.source.FileOperationException
import com.luckycatpaw.luckyfilestv.data.source.FileSource
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.SourceCapabilities
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceMessages
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.Volume
import com.luckycatpaw.luckyfilestv.data.source.VolumeKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The entry point of the UI into the sources, with a stand-in source underneath.
 *
 * What is worth holding here is not the forwarding but the wrapping: every failure has to
 * come back as a Result carrying text a screen can show, and cancellation must not be caught
 * on the way, or a coroutine that was told to stop keeps going.
 */
class FileRepositoryTest {

    private val source = FakeFileSource()

    private val repository = FileRepository(
        sources = FileSourceRegistry(listOf(source)),
        messages = SourceMessagesStub
    )

    @Test
    fun `a listing arrives in the shape the screens consume`() = runTest {
        source.listing = listing(
            entry("Movies", isDirectory = true),
            entry("Film.mkv", size = 42L, lastModified = 7L)
        )

        val content = repository.list("/storage/emulated/0", settings()).getOrThrow()

        assertEquals("Internal storage", content.title)
        assertTrue(content.writable)
        assertEquals(
            listOf(BrowserItem.Folder(name = "Movies", path = "/storage/emulated/0/Movies")),
            content.items.filterIsInstance<BrowserItem.Folder>()
        )
        val file = content.items.filterIsInstance<BrowserItem.File>().single()
        assertEquals(42L, file.size)
        assertEquals(7L, file.lastModified)
    }

    @Test
    fun `a failure comes back as a result carrying text a screen can show`() = runTest {
        source.failure = SourceException.AccessDenied(PATH, SourceOperation.LIST)

        val failure = repository.list("/storage/emulated/0", settings()).exceptionOrNull()

        assertIs<FileOperationException>(failure)
        assertEquals("AccessDenied for LIST", failure.message)
        assertSame(source.failure, failure.cause)
    }

    @Test
    fun `the operation reaches the wording, not just the error`() = runTest {
        // The same exception reads differently depending on what was being attempted, so
        // both have to arrive together.
        source.failure = IOException("disk")

        val renaming = repository.rename("/storage/emulated/0/a", "b").exceptionOrNull()
        val deleting = repository.delete("/storage/emulated/0/a").exceptionOrNull()

        assertEquals("IOException for RENAME", renaming?.message)
        assertEquals("IOException for DELETE", deleting?.message)
    }

    @Test
    fun `a path no source owns fails like any other operation`() = runTest {
        val failure = repository.list("ftp://server/pub", settings()).exceptionOrNull()

        assertIs<FileOperationException>(failure)
        assertIs<SourceException.Unsupported>(failure.cause)
    }

    @Test
    fun `an unparsable path fails rather than reaching a source`() = runTest {
        val failure = repository.getProperties("not a location").exceptionOrNull()

        assertIs<FileOperationException>(failure)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `cancellation is not swallowed on the way out`() = runTest {
        // Turning this into a failed Result would leave the caller believing its coroutine
        // is still alive.
        source.failure = CancellationException("stopped")

        assertFailsWith<CancellationException> { repository.list("/storage/emulated/0", settings()) }
    }

    @Test
    fun `rename and createFolder answer with the new location`() = runTest {
        assertEquals(
            "/storage/emulated/0/renamed.mkv",
            repository.rename("/storage/emulated/0/a.mkv", "renamed.mkv").getOrThrow()
        )
        assertEquals(
            "/storage/emulated/0/Movies",
            repository.createFolder("/storage/emulated/0", "Movies").getOrThrow()
        )
    }

    @Test
    fun `parentOf climbs one level and stops at the top`() = runTest {
        assertEquals("/storage/emulated/0", repository.parentOf("/storage/emulated/0/Movies"))
        assertNull(repository.parentOf("/"))
        // Not a location at all, so there is nothing above it either.
        assertNull(repository.parentOf("not a location"))
    }

    @Test
    fun `isRoot is true only for a location a source hands out as a root`() = runTest {
        source.volumes = listOf(volume("/storage/emulated/0"))

        assertTrue(repository.isRoot("/storage/emulated/0"))
        assertFalse(repository.isRoot("/storage/emulated/0/Movies"))
        assertFalse(repository.isRoot("not a location"))
    }

    @Test
    fun `roots come back as storage items`() = runTest {
        source.volumes = listOf(volume("/storage/emulated/0"), volume("/storage/1234-5678"))

        assertEquals(2, repository.roots().size)
        assertEquals("Internal storage", repository.roots().first().volume.name)
    }

    private fun settings(): FileManagerSettings = FileManagerSettings()

    private fun listing(vararg entries: FileEntry): DirectoryListing = DirectoryListing(
        path = PATH,
        displayName = "Internal storage",
        writable = true,
        entries = entries.toList()
    )

    private fun entry(name: String, isDirectory: Boolean = false, size: Long = 0L, lastModified: Long = 0L): FileEntry =
        FileEntry(
            path = PATH.child(name),
            name = name,
            isDirectory = isDirectory,
            size = size,
            lastModified = lastModified
        )

    private fun volume(path: String): Volume =
        Volume(path = SourcePath.parse(path), name = "Internal storage", kind = VolumeKind.INTERNAL)

    /** Names the error and the operation instead of a resource, so a test can read both. */
    private object SourceMessagesStub : SourceMessages {
        override fun localize(error: Throwable, operation: SourceOperation): String =
            "${error::class.simpleName} for $operation"
    }

    private class FakeFileSource : FileSource {

        override val id: String = SourcePath.LOCAL_SCHEME

        override val capabilities: SourceCapabilities = SourceCapabilities(
            writable = true,
            randomAccessRead = false,
            atomicMove = true,
            cheapMetadata = true,
            requiresNetwork = false
        )

        var failure: Throwable? = null
        var volumes: List<Volume> = emptyList()
        var listing: DirectoryListing? = null
        val calls = mutableListOf<String>()

        override suspend fun roots(): List<Volume> = volumes

        override suspend fun list(path: SourcePath, options: ListOptions): DirectoryListing =
            answer("list") { requireNotNull(listing) }

        override suspend fun stat(path: SourcePath): FileEntry? = answer("stat") { null }

        override suspend fun properties(path: SourcePath): FileProperties = answer("properties") {
            FileProperties(
                name = path.name,
                path = path.value,
                isDirectory = false,
                size = 0L,
                lastModified = 0L,
                fileCount = 0L,
                folderCount = 0L,
                extension = null,
                mimeType = null,
                unreadableDirectoryCount = 0L
            )
        }

        override suspend fun createDirectory(parent: SourcePath, name: String): SourcePath =
            answer("createDirectory") { parent.child(name) }

        override suspend fun rename(path: SourcePath, newName: String): SourcePath =
            answer("rename") { path.sibling(newName) ?: path }

        override suspend fun delete(path: SourcePath) = answer("delete") { }

        // Not nullInputStream: that arrived in API 33 and lint checks the test sources too.
        override suspend fun openInput(path: SourcePath, offset: Long): InputStream =
            answer("openInput") { ByteArrayInputStream(ByteArray(0)) }

        override suspend fun openOutput(path: SourcePath, overwrite: Boolean): OutputStream =
            answer("openOutput") { ByteArrayOutputStream() }

        private inline fun <T> answer(name: String, value: () -> T): T {
            calls += name
            failure?.let { throw it }
            return value()
        }
    }

    private companion object {
        val PATH: SourcePath = SourcePath.parse("/storage/emulated/0")
    }
}
