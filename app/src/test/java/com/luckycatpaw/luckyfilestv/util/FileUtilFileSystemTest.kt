package com.luckycatpaw.luckyfilestv.util

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The half of [FileUtil] that needs a real directory. Kept apart from [FileUtilTest] so the
 * pure checks stay free of a fixture, and so it is obvious which tests can skip themselves.
 */
class FileUtilFileSystemTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `createUniqueDestination hands back the requested name when it is free`() {
        val destination = FileUtil.createUniqueDestination(
            parent = temporaryFolder.root,
            requestedName = "Film.mkv",
            isDirectory = false
        )

        assertEquals("Film.mkv", destination.name)
        assertFalse(destination.exists())
    }

    @Test
    fun `createUniqueDestination counts up past what is already there`() {
        File(temporaryFolder.root, "Film.mkv").writeText("first")
        File(temporaryFolder.root, "Film (1).mkv").writeText("second")

        val destination = FileUtil.createUniqueDestination(
            parent = temporaryFolder.root,
            requestedName = "Film.mkv",
            isDirectory = false
        )

        assertEquals("Film (2).mkv", destination.name)
    }

    @Test
    fun `createUniqueDestination avoids a name another transfer already claimed`() {
        // Nothing is on disk yet: the planning phase hands out destinations for several
        // items before the first one is written, and two of them must not collide.
        val destination = FileUtil.createUniqueDestination(
            parent = temporaryFolder.root,
            requestedName = "Film.mkv",
            isDirectory = false,
            reservedTargets = setOf(File(temporaryFolder.root, "Film.mkv").absolutePath)
        )

        assertEquals("Film (1).mkv", destination.name)
    }

    @Test
    fun `createUniqueDestination treats a dangling symbolic link as an occupied name`() {
        // The existence check deliberately does not follow links. File.exists would report
        // this name as free and the transfer would then fail on a target that is taken.
        danglingLink("Film.mkv")

        val destination = FileUtil.createUniqueDestination(
            parent = temporaryFolder.root,
            requestedName = "Film.mkv",
            isDirectory = false
        )

        assertEquals("Film (1).mkv", destination.name)
    }

    @Test
    fun `isSameOrChild resolves a symbolic link before comparing`() {
        val real = temporaryFolder.newFolder("real")
        val link = File(temporaryFolder.root, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess)

        assertTrue(FileUtil.isSameOrChild(real, File(link, "inside.txt")))
    }

    @Test
    fun `isSameOrChild does not fall for a shared name prefix`() {
        val movies = File(temporaryFolder.root, "Movies")

        assertTrue(FileUtil.isSameOrChild(movies, File(movies, "Film.mkv")))
        assertFalse(FileUtil.isSameOrChild(movies, File(temporaryFolder.root, "MoviesOld")))
    }

    @Test
    fun `moveWithoutReplacing moves a file`() {
        val source = File(temporaryFolder.root, "source.txt").apply { writeText("content") }
        val target = File(temporaryFolder.root, "target.txt")

        FileUtil.moveWithoutReplacing(source, target)

        assertFalse(source.exists())
        assertEquals("content", target.readText())
    }

    @Test
    fun `moveWithoutReplacing moves a directory with what is inside it`() {
        val source = temporaryFolder.newFolder("source")
        File(source, "inside.txt").writeText("content")
        val target = File(temporaryFolder.root, "target")

        FileUtil.moveWithoutReplacing(source, target)

        assertFalse(source.exists())
        assertEquals("content", File(target, "inside.txt").readText())
    }

    @Test
    fun `moveWithoutReplacing refuses an occupied target and leaves both sides alone`() {
        val source = File(temporaryFolder.root, "source.txt").apply { writeText("source") }
        val target = File(temporaryFolder.root, "target.txt").apply { writeText("target") }

        assertFailsWith<FileAlreadyExistsException> { FileUtil.moveWithoutReplacing(source, target) }

        assertEquals("source", source.readText())
        assertEquals("target", target.readText())
    }

    @Test
    fun `moveWithoutReplacing refuses a target name held by a dangling symbolic link`() {
        // This is the case the whole reservation detour exists for. An existence check
        // would call the name free, and rename would then replace the link without a word.
        val source = File(temporaryFolder.root, "source.txt").apply { writeText("content") }
        val link = danglingLink("target.txt")

        assertFailsWith<FileAlreadyExistsException> { FileUtil.moveWithoutReplacing(source, link) }

        assertEquals("content", source.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    /** A symbolic link in the temporary folder pointing at something that is not there. */
    private fun danglingLink(name: String): File {
        val link = File(temporaryFolder.root, name)
        val missing = File(temporaryFolder.root, "missing-target").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), missing) }.isSuccess)
        return link
    }
}
