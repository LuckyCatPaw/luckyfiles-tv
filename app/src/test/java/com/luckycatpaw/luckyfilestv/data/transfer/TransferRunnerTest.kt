package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferProgress
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Carrying out a plan, and what happens when it does not go through.
 *
 * All of this is about failure, because that is where a transfer costs the user something:
 * a source deleted after a copy that did not finish, a file reported as moved that is still
 * only in one place, or nineteen items abandoned because the second one failed.
 */
class TransferRunnerTest {

    private val engine = FakeTransferEngine()

    private val remoteSource = FakeRemoteFileSource()

    private val registry = FileSourceRegistry(listOf(remoteSource))

    private val tally = TransferTally()

    private val progress = mutableListOf<TransferProgress>()

    private val runner = TransferRunner(
        engine = engine,
        messages = StubTransferMessages,
        freeSpace = FreeSpaceCheck(
            messages = StubTransferMessages,
            availableBytes = { Long.MAX_VALUE },
            totalBytes = { Long.MAX_VALUE }
        ),
        targetFor = { path -> TransferTarget.Remote(path, registry) },
        describeFailure = { failure -> failure.message.orEmpty() }
    )

    @Test
    fun `a copy records the target it wrote`() = runTest {
        run(item("a.mkv", size = 10L))

        assertEquals(listOf("smb://nas/target/a.mkv"), completed())
        assertEquals(1, engine.copies)
    }

    @Test
    fun `a move renames where the storage allows it and copies nothing`() = runTest {
        engine.renames = true

        run(item("a.mkv", size = 10L), operation = TransferOperation.MOVE)

        assertEquals(listOf("smb://nas/target/a.mkv"), completed())
        assertEquals(0, engine.copies)
        assertEquals(0, engine.deletes)
    }

    @Test
    fun `a move that cannot rename copies and then removes the source`() = runTest {
        engine.renames = false

        run(item("a.mkv", size = 10L), operation = TransferOperation.MOVE)

        assertEquals(1, engine.copies)
        assertEquals(1, engine.deletes)
        assertEquals(listOf("smb://nas/target/a.mkv"), completed())
    }

    @Test
    fun `a source that will not be removed is a warning, not a failed move`() = runTest {
        // The copy went through. Reporting the move as failed would send the user looking
        // for files that are already there.
        engine.renames = false
        engine.deleteFailure = IOException("busy")

        run(item("a.mkv", size = 10L), operation = TransferOperation.MOVE)

        assertEquals(listOf("smb://nas/target/a.mkv"), completed())
        assertEquals(1, tally.result(cancelled = false).sourceDeleteWarningCount)
        assertTrue(issues().isEmpty())
    }

    @Test
    fun `an item that fails does not take the ones after it`() = runTest {
        engine.failCopyOf = "b.mkv"

        run(item("a.mkv", size = 1L), item("b.mkv", size = 1L), item("c.mkv", size = 1L))

        assertEquals(listOf("smb://nas/target/a.mkv", "smb://nas/target/c.mkv"), completed())
        assertEquals(listOf("write failed"), issues())
    }

    @Test
    fun `an unreadable folder inside a copy is reported and the item still counts`() = runTest {
        engine.unreadable = listOf("smb://nas/media/locked")

        run(item("a.mkv", size = 10L))

        assertEquals(listOf("smb://nas/target/a.mkv"), completed())
        assertEquals(listOf("unreadableSkipped"), issues())
    }

    @Test
    fun `a move with an unreadable directory keeps the source and reports the omission`() = runTest {
        engine.renames = false
        engine.unreadable = listOf("smb://nas/media/locked")

        run(item("folder", size = 10L), operation = TransferOperation.MOVE)

        assertEquals(1, engine.copies)
        assertEquals(0, engine.deletes)
        assertEquals(listOf("unreadableSkipped"), issues())
        assertTrue(completed().isEmpty())
        assertEquals(1, tally.result(cancelled = false).sourceDeleteWarningCount)
    }

    @Test
    fun `a partial move warns once per source and still counts a later successful move`() = runTest {
        engine.unreadable = listOf("smb://nas/media/locked", "smb://nas/media/also-locked")
        engine.renameOnly = setOf("next.mkv")

        run(item("folder", size = 10L), item("next.mkv", size = 1L), operation = TransferOperation.MOVE)

        assertEquals(1, engine.copies)
        assertEquals(0, engine.deletes)
        assertEquals(listOf("smb://nas/target/next.mkv"), completed())
        assertEquals(listOf("unreadableSkipped", "unreadableSkipped"), issues())
        assertEquals(1, tally.result(cancelled = false).sourceDeleteWarningCount)
    }

    @Test
    fun `what the engine could not clean up is counted`() = runTest {
        engine.cleanupWarning = true

        run(item("a.mkv", size = 10L))

        assertEquals(1, tally.result(cancelled = false).cleanupWarningCount)
    }

    @Test
    fun `a move measured late adds its size to the total exactly once`() = runTest {
        // The planner skips anything it expects to rename. A source that changes its mind
        // is measured here, and its size joins the total as one step rather than one per
        // byte written.
        engine.renames = false
        engine.bytesToReport = 10L
        remoteSource.file("smb://nas/media/a.mkv", content = "0123456789")

        run(unmeasured("a.mkv"), operation = TransferOperation.MOVE)

        // Announced at zero, then once more with the size that was just read.
        assertEquals(listOf(0L, 10L), progress.map { it.totalBytes })
    }

    @Test
    fun `progress announces an item before it starts`() = runTest {
        run(item("a.mkv", size = 10L), item("b.mkv", size = 10L))

        assertEquals(listOf(1, 2), progress.map { it.currentItem })
        assertEquals(listOf(2, 2), progress.map { it.totalItems })
        assertEquals(listOf("a.mkv", "b.mkv"), progress.map { it.currentName })
    }

    @Test
    fun `a rename has no rate and a copy has one`() = runTest {
        // A rename moves no bytes, so a speed would be a number made up out of nothing.
        engine.renames = true

        run(item("a.mkv", size = 10L), operation = TransferOperation.MOVE)
        val duringMove = progress.map { it.bytesPerSecond }

        progress.clear()
        engine.bytesToReport = 10L
        run(item("b.mkv", size = 10L), operation = TransferOperation.COPY)

        assertTrue(duringMove.all { it == null })
        assertNotNull(progress.last().bytesPerSecond)
    }

    @Test
    fun `a move that copies bytes shows a rate`() = runTest {
        engine.bytesToReport = 10L

        run(item("a.mkv", size = 10L), operation = TransferOperation.MOVE)

        assertNotNull(progress.last().bytesPerSecond)
        assertTrue(requireNotNull(progress.last().bytesPerSecond) > 0L)
    }

    @Test
    fun `a rename after a copying move does not inherit its rate`() = runTest {
        engine.bytesToReport = 10L
        engine.renameOnly = setOf("b.mkv")

        run(item("a.mkv", size = 10L), item("b.mkv", size = 10L), operation = TransferOperation.MOVE)

        assertTrue(progress.any { it.currentName == "a.mkv" && it.bytesPerSecond != null })
        assertTrue(progress.filter { it.currentName == "b.mkv" }.all { it.bytesPerSecond == null })
        assertEquals(1, engine.copies)
    }

    @Test
    fun `a transfer that does not fit is refused before anything is written`() = runTest {
        val tight = TransferRunner(
            engine = engine,
            messages = StubTransferMessages,
            freeSpace = FreeSpaceCheck(
                messages = StubTransferMessages,
                availableBytes = { 1L },
                totalBytes = { 1_000L }
            ),
            targetFor = { path -> TransferTarget.Remote(path, registry) },
            describeFailure = { failure -> failure.message.orEmpty() }
        )
        engine.renames = false
        remoteSource.file("smb://nas/media/a.mkv", content = "0123456789")

        tight.run(
            request = RunRequest(
                items = listOf(unmeasured("a.mkv")),
                operation = TransferOperation.MOVE,
                totalBytes = 0L,
                targetDirectory = File("/storage/emulated/0")
            ),
            tally = tally,
            onProgress = { progress += it }
        )

        assertEquals(0, engine.copies)
        assertEquals(listOf("notEnoughSpace"), issues())
        assertTrue(completed().isEmpty())
    }

    private suspend fun run(vararg items: PlannedTransfer, operation: TransferOperation = TransferOperation.COPY) =
        runner.run(
            request = RunRequest(
                items = items.toList(),
                operation = operation,
                totalBytes = items.sumOf { it.size ?: 0L },
                targetDirectory = null
            ),
            tally = tally,
            onProgress = { progress += it }
        )

    private fun item(name: String, size: Long): PlannedTransfer = planned(name, size)

    /** As the planner leaves an item it expected to be renamed: no size read yet. */
    private fun unmeasured(name: String): PlannedTransfer = planned(name, size = null)

    private fun planned(name: String, size: Long?): PlannedTransfer = PlannedTransfer(
        source = TransferSource.Remote(SourcePath.parse("smb://nas/media/$name"), registry),
        target = SourcePath.parse("smb://nas/target/$name"),
        replace = false,
        size = size
    )

    private fun completed(): List<String> = tally.result(cancelled = false).completedPaths

    private fun issues(): List<String> = tally.result(cancelled = false).issues.map(TransferIssue::message)

    private class FakeTransferEngine : TransferEngine {

        var renames = false
        var renameOnly: Set<String> = emptySet()
        var deleteFailure: Throwable? = null
        var failCopyOf: String? = null
        var cleanupWarning = false
        var unreadable: List<String> = emptyList()
        var bytesToReport = 0L

        var copies = 0
        var deletes = 0

        override suspend fun copy(request: CopyRequest, onBytesCopied: suspend (Long) -> Unit): TransferItemResult {
            if (request.source.name == failCopyOf) throw IOException("write failed")

            copies++
            if (bytesToReport > 0L) onBytesCopied(bytesToReport)

            return TransferItemResult(
                cleanupWarning = cleanupWarning,
                bytesTransferred = request.totalBytes,
                unreadableDirectories = unreadable
            )
        }

        override suspend fun tryFastMove(
            source: TransferSource,
            target: SourcePath,
            replace: Boolean
        ): TransferItemResult? = if (renames || source.name in renameOnly) {
            TransferItemResult(cleanupWarning = false, bytesTransferred = 0L)
        } else {
            null
        }

        override suspend fun delete(source: TransferSource, copiedEntries: List<CopiedEntry>) {
            deletes++
            deleteFailure?.let { throw it }
        }
    }
}
