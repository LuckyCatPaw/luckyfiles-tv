package com.luckycatpaw.luckyfilestv.data.repository

import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourceMessages
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.local.LocalFileSource
import com.luckycatpaw.luckyfilestv.data.source.local.LocalVolumes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Paths from a real listing must still name the same entry when the UI sends them back.
 * Two names that differ only by trailing whitespace make a wrong lookup destructive.
 */
class FileRepositoryFileSystemTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val repository = FileRepository(
        sources = FileSourceRegistry(listOf(LocalFileSource(volumes = LocalVolumes { emptyList() }))),
        messages = object : SourceMessages {
            override fun localize(error: Throwable, operation: SourceOperation): String = "$operation: ${error.message}"
        }
    )

    @Before
    fun onlyOnPosix() {
        assumeTrue(File.separatorChar == '/')
    }

    @Test
    fun `delete removes the listed file with a trailing space and preserves its neighbour`() = runTest {
        val neighbour = File(temporaryFolder.root, "Film.mkv").apply { writeText("keep") }
        val selected = File(temporaryFolder.root, "Film.mkv ").apply { writeText("selected") }
        val listing = repository.list(temporaryFolder.root.absolutePath, FileManagerSettings()).getOrThrow()
        val item = listing.items.single { it.path == selected.absolutePath }

        repository.delete(item.path).getOrThrow()

        assertFalse(selected.exists())
        assertEquals("keep", neighbour.readText())
    }

    @Test
    fun `rename moves the listed file with a trailing space and preserves its neighbour`() = runTest {
        val neighbour = File(temporaryFolder.root, "Film.mkv").apply { writeText("keep") }
        val selected = File(temporaryFolder.root, "Film.mkv ").apply { writeText("selected") }
        val listing = repository.list(temporaryFolder.root.absolutePath, FileManagerSettings()).getOrThrow()
        val item = listing.items.single { it.path == selected.absolutePath }

        val renamed = repository.rename(item.path, "Renamed.mkv").getOrThrow()

        assertFalse(selected.exists())
        assertEquals("selected", File(renamed).readText())
        assertEquals("keep", neighbour.readText())
    }

    @Test
    fun `opening a folder with a trailing space lists that folder`() = runTest {
        val neighbour = temporaryFolder.newFolder("Movies")
        File(neighbour, "other.mkv").writeText("other")
        val selected = temporaryFolder.newFolder("Movies ")
        File(selected, "selected.mkv").writeText("selected")
        val listing = repository.list(temporaryFolder.root.absolutePath, FileManagerSettings()).getOrThrow()
        val item = listing.items.single { it.path == selected.absolutePath }

        val content = repository.list(item.path, FileManagerSettings()).getOrThrow()

        assertEquals(listOf("selected.mkv"), content.items.map { it.name })
    }
}
