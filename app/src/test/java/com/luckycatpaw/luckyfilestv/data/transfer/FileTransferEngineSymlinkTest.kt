package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import android.content.ContextWrapper
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import java.io.File
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

/** Unsupported links should have the same message for copies and tracked moves. */
class FileTransferEngineSymlinkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a nested symbolic link has the same message with and without move tracking`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val original = temporaryFolder.newFolder("source")
        val referent = File(temporaryFolder.root, "referent.txt").apply { writeText("keep") }
        val link = File(original, "link.txt")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), referent.toPath()) }.isSuccess)
        val source = TransferSource.Local(original, FileTreeWalker())
        val engine = engine()

        for (trackSource in listOf(false, true)) {
            val target = File(temporaryFolder.root, "target-$trackSource")
            val failure = assertFailsWith<IllegalStateException> {
                engine.copy(
                    CopyRequest(source, TransferTarget.Local(target), false, 4L, trackSource = trackSource),
                    onBytesCopied = { }
                )
            }

            assertEquals("symbolicLinksNotSupported", failure.message)
            assertFalse(target.exists())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("keep", referent.readText())
        }
    }

    private fun engine(): FileTransferEngine {
        val journalRoot = temporaryFolder.newFolder("journals")
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir(): File = journalRoot
        }

        return FileTransferEngine(
            context = context,
            fileTreeWalker = FileTreeWalker(),
            sources = FileSourceRegistry(emptyList()),
            messages = StubTransferMessages
        )
    }
}
