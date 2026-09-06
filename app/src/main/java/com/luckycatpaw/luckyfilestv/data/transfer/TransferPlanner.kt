package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.FileConflictPolicy
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflict
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.util.FileUtil
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One item a transfer intends to move, with the destination already decided. */
internal data class PlannedTransfer(
    val source: TransferSource,
    val target: SourcePath,
    val replace: Boolean,
    val size: Long?,
    val invalid: Boolean = false
)

/** What to transfer and where to. */
internal data class PlanRequest(
    val sources: List<TransferSource>,
    val targetLocation: SourcePath,
    /** The target as a directory, `null` when it is on a share. */
    val localTargetDirectory: File?,
    val operation: TransferOperation
)

/** @param cancelled the user answered a conflict question with cancel; nothing was written. */
internal data class TransferPlan(val items: List<PlannedTransfer>, val cancelled: Boolean)

/**
 * Decides what a transfer will do before it writes anything.
 *
 * Every refusal a user sees for a reason other than a failed write is decided here: a source
 * that is gone, a symbolic link, a move into the folder something already sits in, a folder
 * being copied into itself, and what happens to a name that is taken. Nothing on this path
 * touches the file system except through [targetExists], which is why it can be tested.
 *
 * @param targetExists whether a destination is taken, a stat locally and a request on a
 *   share. Injected rather than reached for, since it is the only thing here that leaves the
 *   process.
 */
internal class TransferPlanner(
    private val messages: TransferMessages,
    private val targetExists: suspend (SourcePath) -> Boolean
) {

    suspend fun plan(
        request: PlanRequest,
        tally: TransferTally,
        onConflict: suspend (TransferConflict) -> TransferConflictDecision
    ): TransferPlan {
        // Two paths naming the same item would otherwise be planned twice, and the second
        // would find the destination taken by the first.
        val session = Session(
            request = request,
            sources = request.sources.distinctBy { source ->
                when (source) {
                    is TransferSource.Local ->
                        runCatching { source.file.canonicalPath }.getOrElse { source.pathValue }

                    is TransferSource.Remote -> source.pathValue
                }
            }
        )

        for (source in session.sources) {
            currentCoroutineContext().ensureActive()

            when (val decision = decide(source, session, tally, onConflict)) {
                Decision.Cancelled -> return TransferPlan(items = session.items.toList(), cancelled = true)

                Decision.NothingToDo -> Unit

                is Decision.Transfer -> {
                    session.reservedTargets += decision.item.target.value
                    session.items += decision.item
                }
            }
        }

        return TransferPlan(items = session.items.toList(), cancelled = false)
    }

    /**
     * The four ways an item drops out before a destination is even looked for.
     *
     * Each records its own reason, because "nothing was planned" reads very differently to
     * a user depending on which of them it was: a folder that is gone is a problem, one
     * moved into the folder it already sits in is a job already done.
     */
    private suspend fun decide(
        source: TransferSource,
        session: Session,
        tally: TransferTally,
        onConflict: suspend (TransferConflict) -> TransferConflictDecision
    ): Decision {
        if (!source.exists()) {
            tally.failed(source.pathValue, messages.sourceMissing())
            return Decision.NothingToDo
        }

        val localSource = localFileOf(source)
        val sourceIsSymbolicLink = source.isSymbolicLink()

        if (sourceIsSymbolicLink && session.copying) {
            tally.failed(source.pathValue, messages.symbolicLinksNotSupported())
            return Decision.NothingToDo
        }

        if (!session.copying && parentOf(source, localSource) == session.canonicalTarget) {
            // Moving something into the folder it already sits in is a no-op.
            tally.completed(source.pathValue)
            return Decision.NothingToDo
        }

        val sourceIsDirectory = source.isDirectory()

        if (!sourceIsSymbolicLink && sourceIsDirectory && session.containsItsOwnTarget(source, localSource)) {
            tally.failed(source.pathValue, messages.transferIntoSelf(copying = session.copying))
            return Decision.NothingToDo
        }

        return chooseTarget(source, sourceIsDirectory, session, tally, onConflict)
    }

    /**
     * Where the item lands, and whether the user was asked about it.
     *
     * A name is taken either because something is there or because an earlier item in this
     * same run claimed it — on disk there is nothing to see yet, so the reservation is all
     * that keeps two items apart.
     */
    private suspend fun chooseTarget(
        source: TransferSource,
        sourceIsDirectory: Boolean,
        session: Session,
        tally: TransferTally,
        onConflict: suspend (TransferConflict) -> TransferConflictDecision
    ): Decision {
        val directTarget = session.request.targetLocation.child(source.name)
        val sameTarget = directTarget.value == localFileOf(source)?.absolutePath
        val taken = targetExists(directTarget) || directTarget.value in session.reservedTargets
        val conflict = taken && (session.copying || !sameTarget)

        val policy = resolvePolicy(source, conflict, session, onConflict) ?: return Decision.Cancelled

        if (policy == FileConflictPolicy.SKIP) {
            tally.skipped()
            return Decision.NothingToDo
        }

        // Copying something onto itself with replace asked for leaves it exactly as it is.
        if (session.copying && sameTarget && policy == FileConflictPolicy.REPLACE) {
            tally.completed(source.pathValue)
            return Decision.NothingToDo
        }

        val replace = conflict && policy == FileConflictPolicy.REPLACE

        val target = if (!conflict || replace) {
            directTarget
        } else {
            uniqueDestination(
                parent = session.request.targetLocation,
                requestedName = source.name,
                isDirectory = sourceIsDirectory,
                reservedTargets = session.reservedTargets
            )
        }

        return Decision.Transfer(
            PlannedTransfer(source = source, target = target, replace = replace, size = null)
        )
    }

    /**
     * @return the policy to apply, or `null` when the user cancelled the whole transfer.
     *   Without a conflict nothing is asked and the answer does not matter.
     */
    private suspend fun resolvePolicy(
        source: TransferSource,
        conflict: Boolean,
        session: Session,
        onConflict: suspend (TransferConflict) -> TransferConflictDecision
    ): FileConflictPolicy? {
        if (!conflict) return FileConflictPolicy.KEEP_BOTH

        session.stickyPolicy?.let { return it }

        val decision = onConflict(
            TransferConflict(
                sourceName = source.name,
                targetDirectory = session.request.targetLocation.value,
                multipleItems = session.sources.size > 1
            )
        )

        if (decision.cancelled) return null

        // No policy with no cancellation is the dialog going away; skipping is the answer
        // that changes nothing.
        val policy = decision.policy ?: FileConflictPolicy.SKIP

        if (decision.applyToAll) session.stickyPolicy = policy

        return policy
    }

    private fun localFileOf(source: TransferSource): File? = (source as? TransferSource.Local)?.file

    /** Compared by canonical location, so a symlinked path does not slip past it. */
    private fun parentOf(source: TransferSource, localSource: File?): SourcePath? = if (localSource != null) {
        localSource.parentFile?.canonicalFile?.let { SourcePath.of(it) }
    } else {
        source.location.parent
    }

    private sealed interface Decision {

        /** Recorded in the tally as completed, skipped or failed, and not transferred. */
        data object NothingToDo : Decision

        /** The user answered a conflict question with cancel; nothing after this is planned. */
        data object Cancelled : Decision

        data class Transfer(val item: PlannedTransfer) : Decision
    }

    /** What one run of the planner carries from item to item. */
    private inner class Session(val request: PlanRequest, val sources: List<TransferSource>) {

        val reservedTargets = mutableSetOf<String>()
        val items = mutableListOf<PlannedTransfer>()

        var stickyPolicy: FileConflictPolicy? = null

        val copying: Boolean = request.operation == TransferOperation.COPY

        val canonicalTarget: SourcePath = request.localTargetDirectory
            ?.let { SourcePath.of(it) }
            ?: request.targetLocation

        fun containsItsOwnTarget(source: TransferSource, localSource: File?): Boolean = targetIsInsideSource(
            localSource = localSource,
            localTarget = request.localTargetDirectory,
            source = source,
            target = canonicalTarget
        )
    }

    /**
     * Whether the destination lies inside the folder being transferred.
     *
     * Copying a directory into itself has no end: every file written into the target is a
     * file the walk still has to visit. Locally the two sides are compared as canonical
     * paths, so a symlinked route into the source is caught as well. A share offers nothing
     * to canonicalise against, so the configured locations are compared as they stand —
     * which means the same server reached under two different names (`smb://nas` and
     * `smb://192.168.1.5`) still slips through. The depth limit in the remote walk is what
     * stops that case, this is what turns the ordinary one into a proper message.
     *
     * Mixed transfers cannot contain themselves: a local folder and a share never overlap.
     */
    private fun targetIsInsideSource(
        localSource: File?,
        localTarget: File?,
        source: TransferSource,
        target: SourcePath
    ): Boolean = when {
        localSource != null && localTarget != null -> FileUtil.isSameOrChild(localSource, localTarget)
        localSource == null && !target.isLocal -> target.isSameOrChildOf(source.location)
        else -> false
    }

    /**
     * Finds a free name next to an occupied one, e.g. `Film (1).mkv`.
     *
     * Same rule as locally, only the existence check differs — on a share it is a request
     * rather than a stat.
     */
    private suspend fun uniqueDestination(
        parent: SourcePath,
        requestedName: String,
        isDirectory: Boolean,
        reservedTargets: Set<String>
    ): SourcePath {
        // Not `first { }`: the check is a suspending request to the server, and a sequence
        // predicate cannot suspend.
        for (name in FileUtil.uniqueNameCandidates(requestedName, isDirectory)) {
            val candidate = parent.child(name)

            if (!targetExists(candidate) && candidate.value !in reservedTargets) return candidate
        }

        error("uniqueNameCandidates is infinite")
    }
}
