package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.FileConflictPolicy
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflict
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a transfer decides before it writes anything.
 *
 * Every refusal a user sees for a reason other than a failed write is decided here, and one
 * wrong answer costs files: a folder copied into itself never ends, and a target quietly
 * replaced is data gone. The share is in memory and the only thing reaching a file system is
 * the injected existence check, so all of it is reachable.
 */
class TransferPlannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val remoteSource = FakeRemoteFileSource()

    private val registry = FileSourceRegistry(listOf(remoteSource))

    private val taken = mutableSetOf<String>()

    private val conflicts = mutableListOf<TransferConflict>()

    private var decision = decisionOf(FileConflictPolicy.KEEP_BOTH)

    private val tally = TransferTally()

    private val planner = TransferPlanner(
        messages = StubTransferMessages,
        targetExists = { path -> path.value in taken }
    )

    @Test
    fun `a free name is planned as it stands`() = runTest {
        val plan = plan(source("smb://nas/media/Film.mkv"))

        assertFalse(plan.cancelled)
        assertEquals("smb://nas/target/Film.mkv", plan.items.single().target.value)
        assertFalse(plan.items.single().replace)
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `two paths naming the same item are planned once`() = runTest {
        // Otherwise the second would find the destination taken by the first and ask the
        // user about a conflict with itself.
        val plan = plan(source("smb://nas/media/Film.mkv"), source("smb://nas/media/Film.mkv"))

        assertEquals(1, plan.items.size)
    }

    @Test
    fun `a source that is gone is reported and nothing is planned for it`() = runTest {
        val plan = plan(source("smb://nas/media/gone.mkv", exists = false))

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf("sourceMissing"), messagesOf(tally))
    }

    @Test
    fun `a symbolic link cannot be copied`() = runTest {
        // The only decision here that needs a real file: a share reports no links, so this
        // is local by nature.
        assumeTrue(File.separatorChar == '/')
        val target = temporaryFolder.newFolder("target")
        val link = File(temporaryFolder.root, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess)

        val plan = plan(
            sources = listOf(TransferSource.Local(link, FileTreeWalker())),
            targetLocation = target.absolutePath
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf("symbolicLinksNotSupported"), messagesOf(tally))
    }

    @Test
    fun `moving something into the folder it already sits in is already done`() = runTest {
        val plan = plan(
            sources = listOf(source("smb://nas/target/Film.mkv")),
            operation = TransferOperation.MOVE
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf("smb://nas/target/Film.mkv"), tally.result(cancelled = false).completedPaths)
    }

    @Test
    fun `moving into the same local folder through an alias plans nothing`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val real = temporaryFolder.newFolder("real")
        val original = File(real, "film.mkv").apply { writeText("keep") }
        val alias = File(temporaryFolder.root, "alias")
        assumeTrue(runCatching { Files.createSymbolicLink(alias.toPath(), real.toPath()) }.isSuccess)
        val routes = listOf(original to alias, File(alias, original.name) to real)

        for ((file, target) in routes) {
            val plan = plan(
                sources = listOf(TransferSource.Local(file, FileTreeWalker())),
                targetLocation = target.absolutePath,
                operation = TransferOperation.MOVE,
                // TransferCoordinator.requireDirectory resolves the target before planning.
                localTargetDirectory = target.canonicalFile
            )

            assertTrue(plan.items.isEmpty())
        }

        assertTrue(conflicts.isEmpty())
        assertEquals(routes.map { it.first.absolutePath }, tally.result(cancelled = false).completedPaths)
        assertEquals("keep", original.readText())
    }

    @Test
    fun `a folder cannot be copied into itself`() = runTest {
        // A copy into its own subtree never ends: every file written is one the walk still
        // has to visit.
        val plan = plan(
            sources = listOf(source("smb://nas/target", isDirectory = true)),
            targetLocation = "smb://nas/target/inside"
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf("transferIntoSelf"), messagesOf(tally))
    }

    @Test
    fun `an occupied name is put to the user and kept beside the old one`() = runTest {
        taken += "smb://nas/target/Film.mkv"
        decision = decisionOf(FileConflictPolicy.KEEP_BOTH)

        val plan = plan(source("smb://nas/media/Film.mkv"))

        assertEquals("Film.mkv", conflicts.single().sourceName)
        assertEquals("smb://nas/target/Film (1).mkv", plan.items.single().target.value)
        assertFalse(plan.items.single().replace)
    }

    @Test
    fun `replacing keeps the requested name and says so`() = runTest {
        taken += "smb://nas/target/Film.mkv"
        decision = decisionOf(FileConflictPolicy.REPLACE)

        val plan = plan(source("smb://nas/media/Film.mkv"))

        assertEquals("smb://nas/target/Film.mkv", plan.items.single().target.value)
        assertTrue(plan.items.single().replace)
    }

    @Test
    fun `replacing a remote file with itself plans no writes`() = runTest {
        val path = "smb://nas/target/Film.mkv"
        taken += path
        decision = decisionOf(FileConflictPolicy.REPLACE)

        val plan = plan(source(path))

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf(path), tally.result(cancelled = false).completedPaths)
    }

    @Test
    fun `remote self replacement ignores host and share case`() = runTest {
        val original = "smb://NAS/TARGET/Film.mkv"
        taken += "smb://nas/target/Film.mkv"
        decision = decisionOf(FileConflictPolicy.REPLACE)

        val plan = plan(source(original))

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf(original), tally.result(cancelled = false).completedPaths)
    }

    @Test
    fun `remote moves into the same folder ignore host and share case`() = runTest {
        val original = "smb://NAS/TARGET/Film.mkv"

        val plan = plan(listOf(source(original)), operation = TransferOperation.MOVE)

        assertTrue(plan.items.isEmpty())
        assertTrue(conflicts.isEmpty())
        assertEquals(listOf(original), tally.result(cancelled = false).completedPaths)
    }

    @Test
    fun `remote paths below the share keep their case`() = runTest {
        taken += "smb://nas/media/movies/Film.mkv"
        decision = decisionOf(FileConflictPolicy.REPLACE)

        val plan = plan(
            listOf(source("smb://nas/media/Movies/Film.mkv")),
            targetLocation = "smb://nas/media/movies"
        )

        assertTrue(plan.items.single().replace)
    }

    @Test
    fun `a remote folder cannot be copied into itself through host and share case aliases`() = runTest {
        val plan = plan(
            listOf(source("smb://NAS/MEDIA/folder", isDirectory = true)),
            targetLocation = "smb://nas/media/folder/inside"
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf("transferIntoSelf"), messagesOf(tally))
    }

    @Test
    fun `keeping both when copying a remote file into its own folder finds a new name`() = runTest {
        val path = "smb://nas/target/Film.mkv"
        taken += path

        val plan = plan(source(path))

        assertEquals("smb://nas/target/Film (1).mkv", plan.items.single().target.value)
        assertFalse(plan.items.single().replace)
    }

    @Test
    fun `replacing a remote ancestor is refused for copies and moves`() = runTest {
        taken += "smb://nas/media/A"
        decision = decisionOf(FileConflictPolicy.REPLACE)

        for (operation in TransferOperation.entries) {
            val plan = plan(
                sources = listOf(source("smb://NAS/MEDIA/A/A", isDirectory = true)),
                targetLocation = "smb://nas/media",
                operation = operation
            )

            assertTrue(plan.items.isEmpty())
        }

        assertEquals(listOf("unsafeFileTree", "unsafeFileTree"), messagesOf(tally))
    }

    @Test
    fun `keeping both beside an ancestor remains allowed`() = runTest {
        taken += "smb://nas/media/A"

        val plan = plan(
            sources = listOf(source("smb://nas/media/A/A", isDirectory = true)),
            targetLocation = "smb://nas/media"
        )

        assertEquals("smb://nas/media/A (1)", plan.items.single().target.value)
        assertFalse(plan.items.single().replace)
    }

    @Test
    fun `replacement cannot remove another selected source`() = runTest {
        taken += "smb://nas/target/A"
        decision = decisionOf(FileConflictPolicy.REPLACE)

        val plan = plan(
            source("smb://nas/other/A", isDirectory = true),
            source("smb://nas/target/A/keep.txt")
        )

        assertEquals("smb://nas/target/keep.txt", plan.items.single().target.value)
        assertEquals(listOf("unsafeFileTree"), messagesOf(tally))
    }

    @Test
    fun `a local ancestor is protected through a linked parent`() = runTest {
        assumeTrue(File.separatorChar == '/')
        val real = temporaryFolder.newFolder("real")
        val original = File(real, "A/A").apply { mkdirs() }
        val alias = File(temporaryFolder.root, "alias")
        assumeTrue(runCatching { Files.createSymbolicLink(alias.toPath(), real.toPath()) }.isSuccess)
        taken += File(alias, "A").absolutePath
        decision = decisionOf(FileConflictPolicy.REPLACE)

        val plan = plan(
            sources = listOf(TransferSource.Local(original, FileTreeWalker())),
            targetLocation = alias.absolutePath,
            localTargetDirectory = real
        )

        assertTrue(plan.items.isEmpty())
        assertTrue(original.isDirectory)
        assertEquals(listOf("unsafeFileTree"), messagesOf(tally))
    }

    @Test
    fun `skipping plans nothing and counts`() = runTest {
        taken += "smb://nas/target/Film.mkv"
        decision = decisionOf(FileConflictPolicy.SKIP)

        val plan = plan(source("smb://nas/media/Film.mkv"))

        assertTrue(plan.items.isEmpty())
        assertEquals(1, tally.result(cancelled = false).skippedCount)
    }

    @Test
    fun `apply to all answers the remaining conflicts without asking again`() = runTest {
        taken += setOf("smb://nas/target/a.mkv", "smb://nas/target/b.mkv")
        decision = decisionOf(FileConflictPolicy.SKIP, applyToAll = true)

        val plan = plan(source("smb://nas/media/a.mkv"), source("smb://nas/media/b.mkv"))

        assertEquals(1, conflicts.size)
        assertTrue(plan.items.isEmpty())
        assertEquals(2, tally.result(cancelled = false).skippedCount)
    }

    @Test
    fun `without apply to all every conflict is asked about`() = runTest {
        taken += setOf("smb://nas/target/a.mkv", "smb://nas/target/b.mkv")

        plan(source("smb://nas/media/a.mkv"), source("smb://nas/media/b.mkv"))

        assertEquals(2, conflicts.size)
    }

    @Test
    fun `cancelling a conflict stops the planning there`() = runTest {
        taken += "smb://nas/target/a.mkv"
        decision = decisionOf(policy = null, cancelled = true)

        val plan = plan(source("smb://nas/media/a.mkv"), source("smb://nas/media/b.mkv"))

        assertTrue(plan.cancelled)
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `a name claimed earlier in the same run is not handed out twice`() = runTest {
        // Nothing is on disk yet when the second item is planned, so the reservation is the
        // only thing keeping the two apart.
        val plan = plan(source("smb://nas/media/Film.mkv"), source("smb://nas/other/Film.mkv"))

        assertEquals(
            listOf("smb://nas/target/Film.mkv", "smb://nas/target/Film (1).mkv"),
            plan.items.map { it.target.value }
        )
    }

    private suspend fun plan(vararg sources: TransferSource): TransferPlan = plan(sources = sources.toList())

    private suspend fun plan(
        sources: List<TransferSource>,
        targetLocation: String = "smb://nas/target",
        operation: TransferOperation = TransferOperation.COPY,
        localTargetDirectory: File? = null
    ): TransferPlan = planner.plan(
        request = PlanRequest(
            sources = sources,
            targetLocation = SourcePath.parse(targetLocation),
            localTargetDirectory = localTargetDirectory,
            operation = operation
        ),
        tally = tally,
        onConflict = { conflict ->
            conflicts += conflict
            decision
        }
    )

    private fun source(path: String, exists: Boolean = true, isDirectory: Boolean = false): TransferSource {
        if (exists) {
            if (isDirectory) remoteSource.directory(path) else remoteSource.file(path)
        }
        return TransferSource.Remote(SourcePath.parse(path), registry)
    }

    private fun decisionOf(
        policy: FileConflictPolicy?,
        applyToAll: Boolean = false,
        cancelled: Boolean = false
    ): TransferConflictDecision = TransferConflictDecision(policy, applyToAll, cancelled)

    private fun messagesOf(tally: TransferTally): List<String> =
        tally.result(cancelled = false).issues.map(TransferIssue::message)
}
