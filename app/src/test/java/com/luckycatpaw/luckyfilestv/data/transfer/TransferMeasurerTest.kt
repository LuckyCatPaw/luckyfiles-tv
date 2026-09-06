package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reading how big a transfer will be.
 *
 * The total exists for the progress bar, and the two things that can go wrong with it are
 * both here: measuring something that will not be copied, which is what used to make moving
 * a large folder look like it had stalled, and letting one unmeasurable item take the other
 * nineteen down with it.
 */
class TransferMeasurerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val remoteSource = FakeRemoteFileSource()

    private val registry = FileSourceRegistry(listOf(remoteSource))

    private val tally = TransferTally()

    private var renamesInPlace = false

    private val measurer = TransferMeasurer(
        messages = StubTransferMessages,
        willRenameInPlace = { renamesInPlace },
        describeFailure = { failure -> failure::class.simpleName.orEmpty() }
    )

    @Test
    fun `every item is measured and the sizes add up`() = runTest {
        remoteSource.file("smb://nas/media/a.mkv", content = "aa")
        remoteSource.directory("smb://nas/media/season")
        remoteSource.file("smb://nas/media/season/b.mkv", content = "bbb")

        val measurement = measure(item("smb://nas/media/a.mkv"), item("smb://nas/media/season"))

        assertEquals(listOf(2L, 3L), measurement.items.map { it.size })
        assertEquals(5L, measurement.totalBytes)
    }

    @Test
    fun `a move that will only be renamed is not measured at all`() = runTest {
        // Walking a large folder for a number nothing displays is what made a move look
        // like it had stalled.
        renamesInPlace = true
        remoteSource.file("smb://nas/media/a.mkv", content = "aa")

        val measurement = measure(item("smb://nas/media/a.mkv"), operation = TransferOperation.MOVE)

        assertNull(measurement.items.single().size)
        assertEquals(0L, measurement.totalBytes)
    }

    @Test
    fun `a copy is measured even where a move would only rename`() = runTest {
        renamesInPlace = true
        remoteSource.file("smb://nas/media/a.mkv", content = "aa")

        val measurement = measure(item("smb://nas/media/a.mkv"), operation = TransferOperation.COPY)

        assertEquals(2L, measurement.items.single().size)
    }

    @Test
    fun `an item that cannot be measured is set aside, not thrown`() = runTest {
        // One unreadable folder must not cancel a selection of twenty.
        remoteSource.file("smb://nas/media/a.mkv", content = "aa")

        val measurement = measure(item("smb://nas/media/gone"), item("smb://nas/media/a.mkv"))

        assertTrue(measurement.items.first().invalid)
        assertFalse(measurement.items.last().invalid)
        assertEquals(2L, measurement.totalBytes)
        assertEquals(listOf("NoSuchElementException"), messages())
    }

    @Test
    fun `an item set aside contributes nothing to the total`() = runTest {
        val measurement = measure(item("smb://nas/media/gone"))

        assertEquals(0L, measurement.totalBytes)
        assertNull(measurement.items.single().size)
    }

    @Test
    fun `a tree holding a symbolic link is set aside with its own reason`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val root = temporaryFolder.newFolder("root")
        File(root, "a.txt").writeText("abc")
        assumeTrue(
            runCatching {
                Files.createSymbolicLink(File(root, "link").toPath(), File(root, "a.txt").toPath())
            }.isSuccess
        )

        val measurement = measure(localItem(root))

        assertTrue(measurement.items.single().invalid)
        assertEquals(0L, measurement.totalBytes)
        assertEquals(listOf("symbolicLinksNotSupported"), messages())
    }

    private suspend fun measure(
        vararg items: PlannedTransfer,
        operation: TransferOperation = TransferOperation.COPY
    ): Measurement = measurer.measure(items.toList(), operation, tally)

    private fun item(path: String): PlannedTransfer = PlannedTransfer(
        source = TransferSource.Remote(SourcePath.parse(path), registry),
        target = SourcePath.parse("smb://nas/target").child(SourcePath.parse(path).name),
        replace = false,
        size = null
    )

    private fun localItem(file: File): PlannedTransfer = PlannedTransfer(
        source = TransferSource.Local(file, FileTreeWalker()),
        target = SourcePath.parse("smb://nas/target").child(file.name),
        replace = false,
        size = null
    )

    private fun messages(): List<String> = tally.result(cancelled = false).issues.map(TransferIssue::message)
}
