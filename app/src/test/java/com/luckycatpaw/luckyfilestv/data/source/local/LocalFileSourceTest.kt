package com.luckycatpaw.luckyfilestv.data.source.local

import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.SortOptions
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.Volume
import com.luckycatpaw.luckyfilestv.data.source.VolumeKind
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * On-device storage against a real temporary directory.
 *
 * The whole class skips off a POSIX host: [SourcePath] only accepts absolute paths starting
 * with a slash, so on Windows every path the temporary folder produces is rejected before
 * the source ever sees it. The Linux runner in CI covers these.
 */
class LocalFileSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = LocalFileSource(volumes = LocalVolumes { volumes })

    private var volumes: List<Volume> = emptyList()

    @Before
    fun onlyOnPosix() {
        assumeTrue(File.separatorChar == '/')
    }

    @Test
    fun `roots come from the volumes it was given`() = runTest {
        volumes = listOf(volume(temporaryFolder.root, "Internal storage"))

        assertEquals(listOf("Internal storage"), source.roots().map { it.name })
    }

    @Test
    fun `list returns the entries sorted and says whether the directory can be written`() = runTest {
        File(temporaryFolder.root, "b.txt").writeText("bb")
        File(temporaryFolder.root, "a.txt").writeText("a")
        File(temporaryFolder.root, "sub").mkdir()

        val listing = source.list(path(temporaryFolder.root), options())

        assertEquals(listOf("sub", "a.txt", "b.txt"), listing.entries.map { it.name })
        assertTrue(listing.writable)
    }

    @Test
    fun `list keeps the path the caller navigated to`() = runTest {
        // Canonicalisation happens inside; handing a different path back would change the
        // identity of the screen under the caller.
        val requested = path(temporaryFolder.root)

        assertEquals(requested, source.list(requested, options()).path)
    }

    @Test
    fun `list reads sizes only when it sorts on them`() = runTest {
        File(temporaryFolder.root, "a.txt").writeText("12345")

        val byName = source.list(path(temporaryFolder.root), options(FileSortMode.NAME))
        val bySize = source.list(path(temporaryFolder.root), options(FileSortMode.SIZE))

        assertEquals(0L, byName.entries.single().size)
        assertEquals(5L, bySize.entries.single().size)
    }

    @Test
    fun `list hides folder artwork when it is asked to`() = runTest {
        File(temporaryFolder.root, "folder.jpg").writeText("art")
        File(temporaryFolder.root, "film.mkv").writeText("film")

        val hidden = source.list(path(temporaryFolder.root), options(hideFolderJpg = true))
        val shown = source.list(path(temporaryFolder.root), options(hideFolderJpg = false))

        assertEquals(listOf("film.mkv"), hidden.entries.map { it.name })
        assertEquals(listOf("film.mkv", "folder.jpg"), shown.entries.map { it.name })
    }

    @Test
    fun `an unfiltered listing includes dot files without changing the browser default`() = runTest {
        File(temporaryFolder.root, ".nomedia").writeText("hidden")
        File(temporaryFolder.root, ".config").mkdir()
        File(temporaryFolder.root, "film.mkv").writeText("film")

        val browser = source.list(path(temporaryFolder.root), options())
        val transfer = source.list(path(temporaryFolder.root), options().copy(showHidden = true))

        assertEquals(listOf("film.mkv"), browser.entries.map { it.name })
        assertEquals(setOf(".nomedia", ".config", "film.mkv"), transfer.entries.map { it.name }.toSet())
    }

    @Test
    fun `list refuses what is not a readable directory`() = runTest {
        val file = File(temporaryFolder.root, "film.mkv").apply { writeText("film") }
        val missing = File(temporaryFolder.root, "gone")

        assertFailsWith<SourceException.NotFound> { source.list(path(missing), options()) }
        assertFailsWith<SourceException.NotADirectory> { source.list(path(file), options()) }
    }

    @Test
    fun `stat answers for what is there and null for what is not`() = runTest {
        val file = File(temporaryFolder.root, "film.mkv").apply { writeText("film") }

        val entry = source.stat(path(file))

        assertEquals("film.mkv", entry?.name)
        assertEquals(4L, entry?.size)
        assertFalse(entry?.isDirectory == true)
        assertNull(source.stat(path(File(temporaryFolder.root, "gone"))))
    }

    @Test
    fun `properties count what is below a directory`() = runTest {
        File(temporaryFolder.root, "a.txt").writeText("abc")
        val sub = File(temporaryFolder.root, "sub").apply { mkdir() }
        File(sub, "b.bin").writeText("12345")

        val properties = source.properties(path(temporaryFolder.root))

        assertTrue(properties.isDirectory)
        assertEquals(8L, properties.size)
        assertEquals(2L, properties.fileCount)
        assertEquals(1L, properties.folderCount)
        assertNull(properties.mimeType)
    }

    @Test
    fun `createDirectory makes one and refuses a name that is taken`() = runTest {
        val created = source.createDirectory(path(temporaryFolder.root), "Movies")

        assertTrue(File(temporaryFolder.root, "Movies").isDirectory)
        assertEquals("Movies", created.name)
        assertFailsWith<SourceException.AlreadyExists> {
            source.createDirectory(path(temporaryFolder.root), "Movies")
        }
    }

    @Test
    fun `createDirectory refuses a name that would leave the directory`() = runTest {
        // The name is a name, not a path: anything else is a way out of the tree.
        assertFailsWith<SourceException.InvalidName> {
            source.createDirectory(path(temporaryFolder.root), "../escaped")
        }
        assertFailsWith<SourceException.InvalidName> {
            source.createDirectory(path(temporaryFolder.root), "  ")
        }
    }

    @Test
    fun `rename moves the entry and reports the new path`() = runTest {
        val file = File(temporaryFolder.root, "old.txt").apply { writeText("content") }

        val renamed = source.rename(path(file), "new.txt")

        assertEquals("new.txt", renamed.name)
        assertFalse(file.exists())
        assertEquals("content", File(temporaryFolder.root, "new.txt").readText())
    }

    @Test
    fun `rename to the same name is not a failure`() = runTest {
        val file = File(temporaryFolder.root, "film.mkv").apply { writeText("film") }

        assertEquals("film.mkv", source.rename(path(file), "film.mkv").name)
        assertEquals("film", file.readText())
    }

    @Test
    fun `renaming a symbolic link leaves its target untouched`() = runTest {
        val original = File(temporaryFolder.newFolder("elsewhere"), "original.txt").apply { writeText("keep") }
        val link = File(temporaryFolder.root, "link.txt")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), original.toPath()) }.isSuccess)

        val renamed = source.rename(path(link), "renamed.txt").toFile()

        assertEquals(File(temporaryFolder.root, "renamed.txt"), renamed)
        assertTrue(Files.isSymbolicLink(renamed.toPath()))
        assertEquals(original.toPath(), Files.readSymbolicLink(renamed.toPath()))
        assertEquals("keep", original.readText())
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a dangling symbolic link can be renamed`() = runTest {
        val missing = File(temporaryFolder.root, "missing.txt")
        val link = File(temporaryFolder.root, "link.txt")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), missing.toPath()) }.isSuccess)

        val renamed = source.rename(path(link), "renamed.txt").toFile()

        assertTrue(Files.isSymbolicLink(renamed.toPath()))
        assertEquals(missing.toPath(), Files.readSymbolicLink(renamed.toPath()))
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(missing.exists())
    }

    @Test
    fun `rename onto an occupied name leaves both sides alone`() = runTest {
        val file = File(temporaryFolder.root, "old.txt").apply { writeText("old") }
        val taken = File(temporaryFolder.root, "taken.txt").apply { writeText("taken") }

        assertFailsWith<SourceException.AlreadyExists> { source.rename(path(file), "taken.txt") }

        assertEquals("old", file.readText())
        assertEquals("taken", taken.readText())
    }

    @Test
    fun `move relocates an entry inside one volume`() = runTest {
        volumes = listOf(volume(temporaryFolder.root, "Internal storage"))
        val file = File(temporaryFolder.root, "film.mkv").apply { writeText("film") }
        val target = File(temporaryFolder.newFolder("Movies"), "film.mkv")

        source.move(path(file), path(target))

        assertFalse(file.exists())
        assertEquals("film", target.readText())
    }

    @Test
    fun `move leaves different volumes to the transfer engine`() = runTest {
        val internal = temporaryFolder.newFolder("internal")
        val external = temporaryFolder.newFolder("external")
        volumes = listOf(volume(internal, "Internal storage"), volume(external, "USB"))
        val file = File(external, "film.mkv").apply { writeText("film") }
        val target = File(internal, "film.mkv")

        // Both fixtures live on one host filesystem. The source must still respect the
        // volume boundary instead of letting Files.move perform an unreported transfer.
        assertFailsWith<SourceException.Unsupported> { source.move(path(file), path(target)) }

        assertEquals("film", file.readText())
        assertFalse(target.exists())
    }

    @Test
    fun `a link to another volume can move inside its own volume through a parent alias`() = runTest {
        val internal = temporaryFolder.newFolder("internal")
        val external = temporaryFolder.newFolder("external")
        volumes = listOf(volume(internal, "Internal storage"), volume(external, "USB"))
        val referent = File(external, "film.mkv").apply { writeText("keep") }
        val link = File(internal, "film.mkv")
        val alias = File(temporaryFolder.root, "alias")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), referent.toPath()) }.isSuccess)
        assumeTrue(runCatching { Files.createSymbolicLink(alias.toPath(), internal.toPath()) }.isSuccess)
        val target = File(File(internal, "archive").apply { mkdir() }, "film.mkv")
        val requested = path(File(alias, link.name))

        assertTrue(source.canMoveWithoutCopy(requested, path(target)))
        source.move(requested, path(target))

        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertEquals(referent.toPath(), Files.readSymbolicLink(target.toPath()))
        assertEquals("keep", referent.readText())
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a link cannot bypass a volume boundary by pointing into the destination volume`() = runTest {
        val internal = temporaryFolder.newFolder("internal")
        val external = temporaryFolder.newFolder("external")
        volumes = listOf(volume(internal, "Internal storage"), volume(external, "USB"))
        val referent = File(external, "original.mkv").apply { writeText("keep") }
        val link = File(internal, "film.mkv")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), referent.toPath()) }.isSuccess)
        val target = File(external, link.name)

        assertFalse(source.canMoveWithoutCopy(path(link), path(target)))
        assertFailsWith<SourceException.Unsupported> { source.move(path(link), path(target)) }

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertFalse(Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", referent.readText())
    }

    @Test
    fun `delete removes an entry and reports one that is not there`() = runTest {
        val file = File(temporaryFolder.root, "film.mkv").apply { writeText("film") }

        source.delete(path(file))

        assertFalse(file.exists())
        assertFailsWith<SourceException.NotFound> { source.delete(path(file)) }
    }

    @Test
    fun `openInput reads from the offset it was given`() = runTest {
        val file = File(temporaryFolder.root, "film.mkv").apply { writeText("0123456789") }

        val fromStart = source.openInput(path(file), offset = 0L).use { it.readBytes() }
        val fromMiddle = source.openInput(path(file), offset = 4L).use { it.readBytes() }

        assertEquals("0123456789", String(fromStart))
        assertEquals("456789", String(fromMiddle))
    }

    @Test
    fun `openOutput writes and only replaces when it is allowed to`() = runTest {
        val file = File(temporaryFolder.root, "film.mkv")

        source.openOutput(path(file), overwrite = false).use { it.write("first".toByteArray()) }

        assertEquals("first", file.readText())
        assertFailsWith<SourceException.AlreadyExists> { source.openOutput(path(file), overwrite = false) }

        source.openOutput(path(file), overwrite = true).use { it.write("second".toByteArray()) }

        assertEquals("second", file.readText())
    }

    private fun path(file: File): SourcePath = SourcePath.of(file)

    private fun options(mode: FileSortMode = FileSortMode.NAME, hideFolderJpg: Boolean = false): ListOptions =
        ListOptions(sort = SortOptions(mode = mode), hideFolderJpg = hideFolderJpg)

    private fun volume(directory: File, name: String): Volume =
        Volume(path = SourcePath.of(directory), name = name, kind = VolumeKind.INTERNAL)
}
