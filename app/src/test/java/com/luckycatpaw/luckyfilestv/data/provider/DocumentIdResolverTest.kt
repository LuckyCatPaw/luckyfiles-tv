package com.luckycatpaw.luckyfilestv.data.provider

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentIdResolverTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = DocumentIdResolver()

    @Test
    fun `a file survives the trip out to another app and back`() {
        val file = File(temporaryFolder.root, "Film.mkv").apply { writeText("film") }

        val documentId = resolver.toDocumentId(file)

        assertEquals(file.canonicalFile, resolver.fromDocumentId(documentId))
    }

    @Test
    fun `the id carries nothing a URL would object to`() {
        // A document ID travels inside a content URI, so the padding and the two characters
        // the standard alphabet uses would have to be escaped on the way.
        val documentId = resolver.toDocumentIdFromCanonicalPath("/storage/emulated/0/Movies/ab")

        assertTrue(documentId.startsWith("path:"))
        assertTrue(documentId.removePrefix("path:").matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `both ways of asking produce the same id`() {
        val file = File(temporaryFolder.root, "Film.mkv").apply { writeText("film") }

        assertEquals(
            resolver.toDocumentId(file),
            resolver.toDocumentIdFromCanonicalPath(file.canonicalPath)
        )
    }

    @Test
    fun `a path with characters outside ASCII comes back unchanged`() {
        // Encoded as UTF-8 bytes, so a name needing four of them per character is the case
        // worth pinning. No file is created: the filesystem's own encoding is not the point.
        val name = "/storage/emulated/0/Grüße 🎬.mkv"

        val documentId = resolver.toDocumentIdFromCanonicalPath(name)

        assertEquals(File(name).canonicalFile, resolver.fromDocumentId(documentId))
    }

    @Test
    fun `an id that is not one of ours is refused`() {
        // Document IDs arrive from other apps, so none of this can be assumed well formed.
        assertFailsWith<IllegalArgumentException> { resolver.fromDocumentId("") }
        assertFailsWith<IllegalArgumentException> { resolver.fromDocumentId("/storage/emulated/0") }
        assertFailsWith<IllegalArgumentException> { resolver.fromDocumentId("primary:Movies") }
        assertFailsWith<IllegalArgumentException> { resolver.fromDocumentId("path:not base64") }
    }

    @Test
    fun `the snapshot lists the roots it was given`() {
        val internal = File("/storage/emulated/0")
        val usb = File("/storage/1234-5678")

        val snapshot = snapshot(internal, usb)

        assertEquals(setOf(internal.path, usb.path), snapshot.rootPaths)
    }

    @Test
    fun `every root contributes the two folders SAF keeps to itself`() {
        val internal = File("/storage/emulated/0")

        val restricted = snapshot(internal).restrictedRoots.map { it.path }.toSet()

        assertEquals(
            setOf(
                File(internal, "Android/data").canonicalFile.path,
                File(internal, "Android/obb").canonicalFile.path
            ),
            restricted
        )
    }

    @Test
    fun `a root and its Download folder cannot be handed out as a tree`() {
        // Granting either would hand over everything below it in one go.
        val internal = File("/storage/emulated/0")

        val blocked = snapshot(internal).blockedTreePaths

        assertTrue(blocked.contains(internal.path))
        assertTrue(blocked.contains(File(internal, "Download").canonicalFile.path))
    }

    private fun snapshot(vararg roots: File): DocumentIdResolver.ManagedStorageSnapshot =
        DocumentIdResolver.ManagedStorageSnapshot(roots = roots.toList(), namesByRootPath = emptyMap())
}
