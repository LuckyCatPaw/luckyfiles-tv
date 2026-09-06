package com.luckycatpaw.luckyfilestv.data.source

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SourcePathTest {

    @Test
    fun `local path exposes scheme name and segments`() {
        val path = SourcePath.parse("/storage/emulated/0/Movies")

        assertTrue(path.isLocal)
        assertEquals(SourcePath.LOCAL_SCHEME, path.scheme)
        assertEquals("", path.authority)
        assertEquals("Movies", path.name)
        assertEquals(listOf("storage", "emulated", "0", "Movies"), path.segments)
        assertFalse(path.isRoot)
    }

    @Test
    fun `local root has no parent and no segments`() {
        val root = SourcePath.parse("/")

        assertTrue(root.isRoot)
        assertNull(root.parent)
        assertEquals("", root.name)
        assertEquals(emptyList<String>(), root.segments)
    }

    @Test
    fun `parent of a top level folder is the local root`() {
        assertEquals("/", SourcePath.parse("/storage").parent?.value)
        assertEquals("/storage/emulated", SourcePath.parse("/storage/emulated/0").parent?.value)
    }

    @Test
    fun `remote path splits scheme authority and name`() {
        val path = SourcePath.parse("smb://nas/media/Movies")

        assertFalse(path.isLocal)
        assertEquals("smb", path.scheme)
        assertEquals("nas", path.authority)
        assertEquals("Movies", path.name)
        assertEquals(listOf("media", "Movies"), path.segments)
        assertEquals("smb://nas/media", path.parent?.value)
    }

    @Test
    fun `remote root is the bare authority`() {
        val root = SourcePath.remote(scheme = "smb", authority = "nas")

        assertEquals("smb://nas", root.value)
        assertTrue(root.isRoot)
        assertNull(root.parent)
        assertEquals("", root.name)
        assertEquals(emptyList<String>(), root.segments)
    }

    @Test
    fun `remote drops the separators around the path`() {
        assertEquals("smb://nas/media/Movies", SourcePath.remote("smb", "nas", "/media/Movies/").value)
        assertFailsWith<IllegalArgumentException> { SourcePath.remote("smb", " ") }
        assertFailsWith<IllegalArgumentException> { SourcePath.remote("", "nas") }
    }

    @Test
    fun `parse normalises trailing separators and surrounding space`() {
        assertEquals("/storage/emulated/0", SourcePath.parse("  /storage/emulated/0/  ").value)
        assertEquals("smb://nas", SourcePath.parse("smb://nas/").value)
        assertEquals("/", SourcePath.parse("/").value)
    }

    @Test
    fun `parse rejects what belongs to no source`() {
        assertFailsWith<IllegalArgumentException> { SourcePath.parse("") }
        assertFailsWith<IllegalArgumentException> { SourcePath.parse("   ") }
        assertFailsWith<IllegalArgumentException> { SourcePath.parse("Movies/Trailer.mkv") }
        assertNull(SourcePath.parseOrNull("Movies/Trailer.mkv"))
    }

    @Test
    fun `child does not double the separator at a root`() {
        assertEquals("/Movies", SourcePath.parse("/").child("Movies").value)
        assertEquals("/storage/Movies", SourcePath.parse("/storage").child("Movies").value)
        assertEquals("smb://nas/Movies", SourcePath.remote("smb", "nas").child("Movies").value)
    }

    @Test
    fun `sibling stays in the same directory and is null at the root`() {
        assertEquals("/storage/Music", SourcePath.parse("/storage/Movies").sibling("Music")?.value)
        assertNull(SourcePath.parse("/").sibling("Movies"))
    }

    @Test
    fun `isSameOrChildOf does not fall for a shared name prefix`() {
        val movies = SourcePath.parse("/storage/Movies")

        assertTrue(SourcePath.parse("/storage/Movies").isSameOrChildOf(movies))
        assertTrue(SourcePath.parse("/storage/Movies/2024/Film.mkv").isSameOrChildOf(movies))
        assertFalse(SourcePath.parse("/storage/MoviesOld").isSameOrChildOf(movies))
        assertFalse(SourcePath.parse("/storage").isSameOrChildOf(movies))
    }

    @Test
    fun `isSameOrChildOf keeps the sources apart`() {
        val localMedia = SourcePath.parse("/media")
        val remoteMedia = SourcePath.remote("smb", "nas", "media")

        assertFalse(SourcePath.parse("smb://nas/media/Film.mkv").isSameOrChildOf(localMedia))
        assertFalse(SourcePath.parse("/media/Film.mkv").isSameOrChildOf(remoteMedia))
        assertTrue(SourcePath.parse("smb://nas/media/Film.mkv").isSameOrChildOf(remoteMedia))
    }

    @Test
    fun `the local root contains every local location and nothing remote`() {
        val root = SourcePath.parse("/")

        assertTrue(SourcePath.parse("/storage/Movies").isSameOrChildOf(root))
        assertFalse(SourcePath.parse("smb://nas/media").isSameOrChildOf(root))
    }

    @Test
    fun `extension is lower case and empty when there is none`() {
        assertEquals("mkv", SourcePath.parse("/movies/Film.MKV").extension)
        assertEquals("", SourcePath.parse("/movies/Film").extension)
        assertEquals("", SourcePath.parse("/").extension)
    }

    // Records what the code does today rather than endorsing it: a name that is nothing but
    // a suffix reports that suffix as its extension, so `.gitignore` looks like a gitignore
    // file to everything that picks icons or mime types from here.
    @Test
    fun `a name that is only a suffix currently reports it as the extension`() {
        assertEquals("gitignore", SourcePath.parse("/storage/.gitignore").extension)
    }

    @Test
    fun `toFile is refused for remote locations`() {
        assertEquals(File("/storage/Movies"), SourcePath.parse("/storage/Movies").toFile())
        assertFailsWith<IllegalArgumentException> { SourcePath.remote("smb", "nas", "media").toFile() }
    }

    /**
     * [SourcePath.of] goes through [File.getAbsolutePath], the one thing in this class that
     * the host platform answers differently: on Windows the same literal comes back as
     * `C:\storage\Movies`. Everything the class sees in production is an Android path, so
     * the expectation stays POSIX and the check skips itself elsewhere. The CI runner is
     * Linux, so it runs there.
     */
    @Test
    fun `of normalises what File hands over`() {
        assumeTrue(File.separatorChar == '/')

        assertEquals("/storage/Movies", SourcePath.of(File("/storage/Movies/")).value)
    }
}
