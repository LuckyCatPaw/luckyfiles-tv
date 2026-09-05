package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import android.os.storage.StorageManager
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeCycleException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeOutsideRootException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceMessages
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.FileConflictPolicy
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferCancelledException
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflict
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferProgress
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult
import com.luckycatpaw.luckyfilestv.util.FileUtil
import com.luckycatpaw.luckyfilestv.util.formatBytes
import com.luckycatpaw.luckyfilestv.util.safeAdd
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class TransferCoordinator(
    context: Context,
    private val fileTreeWalker: FileTreeWalker = FileTreeWalker(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val sources: FileSourceRegistry by lazy {
        FileSourceRegistry.create(appContext, fileTreeWalker = fileTreeWalker)
    }

    private val appContext = context.applicationContext
    private val sourceMessages = SourceMessages(appContext)
    private val transferEngine by lazy {
        FileTransferEngine(
            context = appContext,
            fileTreeWalker = fileTreeWalker,
            sources = sources
        )
    }

    suspend fun execute(
        sourcePaths: List<String>,
        targetDirectoryPath: String,
        operation: TransferOperation,
        onConflict: suspend (TransferConflict) -> TransferConflictDecision,
        onProgress: suspend (TransferProgress) -> Unit
    ): TransferResult = withContext(ioDispatcher) {
        val completedPaths = mutableListOf<String>()
        val issues = mutableListOf<TransferIssue>()
        var skippedCount = 0
        var cleanupWarningCount = 0
        var sourceDeleteWarningCount = 0

        try {
            val targetLocation = SourcePath.parse(targetDirectoryPath)
            val targetDirectory = if (targetLocation.isLocal) requireDirectory(targetDirectoryPath) else null
            val canonicalTargetLocation = targetDirectory?.let { SourcePath.of(it) } ?: targetLocation
            val uniqueSources = sourcePaths
                .map(::transferSourceFor)
                .distinctBy { source ->
                    when (source) {
                        is TransferSource.Local ->
                            runCatching { source.file.canonicalPath }.getOrElse { source.pathValue }

                        is TransferSource.Remote -> source.pathValue
                    }
                }
            val plannedItems = mutableListOf<PlannedTransfer>()
            val reservedTargets = mutableSetOf<String>()
            var stickyPolicy: FileConflictPolicy? = null
            var cancelled = false

            for (source in uniqueSources) {
                currentCoroutineContext().ensureActive()

                if (!source.exists()) {
                    issues += TransferIssue(
                        sourcePath = source.pathValue,
                        message = appContext.getString(R.string.source_missing)
                    )
                    continue
                }

                val localSource = (source as? TransferSource.Local)?.file
                val sourceIsSymbolicLink = source.isSymbolicLink()

                if (sourceIsSymbolicLink && operation == TransferOperation.COPY) {
                    issues += TransferIssue(
                        sourcePath = source.pathValue,
                        message = appContext.getString(R.string.symbolic_links_not_supported)
                    )
                    continue
                }

                // Moving something into the folder it already sits in is a no-op. Compared
                // by canonical location, so a symlinked path does not slip past it.
                val sourceParent = if (localSource != null) {
                    localSource.parentFile?.canonicalFile?.let { SourcePath.of(it) }
                } else {
                    source.location.parent
                }

                if (
                    operation == TransferOperation.MOVE &&
                    sourceParent == canonicalTargetLocation
                ) {
                    completedPaths += source.pathValue
                    continue
                }

                val sourceIsDirectory = source.isDirectory()

                if (
                    !sourceIsSymbolicLink &&
                    sourceIsDirectory &&
                    targetIsInsideSource(
                        localSource = localSource,
                        localTarget = targetDirectory,
                        source = source,
                        target = canonicalTargetLocation
                    )
                ) {
                    issues += TransferIssue(
                        sourcePath = source.pathValue,
                        message = appContext.getString(
                            if (operation == TransferOperation.COPY) {
                                R.string.copy_into_self
                            } else {
                                R.string.move_into_self
                            }
                        )
                    )
                    continue
                }

                val directTarget = targetLocation.child(source.name)
                val sameTarget = directTarget.value == localSource?.absolutePath
                val conflict = (
                    targetExists(directTarget) || directTarget.value in reservedTargets
                    ) &&
                    (operation == TransferOperation.COPY || !sameTarget)
                var policy = if (conflict) {
                    stickyPolicy ?: FileConflictPolicy.KEEP_BOTH
                } else {
                    FileConflictPolicy.KEEP_BOTH
                }

                if (conflict && stickyPolicy == null) {
                    val decision = onConflict(
                        TransferConflict(
                            sourceName = source.name,
                            targetDirectory = targetLocation.value,
                            multipleItems = uniqueSources.size > 1
                        )
                    )

                    if (decision.cancelled) {
                        cancelled = true
                        break
                    }

                    policy = decision.policy ?: FileConflictPolicy.SKIP

                    if (decision.applyToAll) {
                        stickyPolicy = policy
                    }
                }

                if (policy == FileConflictPolicy.SKIP) {
                    skippedCount++
                    continue
                }

                if (
                    operation == TransferOperation.COPY &&
                    sameTarget &&
                    policy == FileConflictPolicy.REPLACE
                ) {
                    completedPaths += source.pathValue
                    continue
                }

                val target = when {
                    !conflict -> directTarget

                    policy == FileConflictPolicy.REPLACE -> directTarget

                    else -> uniqueDestination(
                        parent = targetLocation,
                        requestedName = source.name,
                        isDirectory = sourceIsDirectory,
                        reservedTargets = reservedTargets
                    )
                }

                reservedTargets += target.value

                plannedItems += PlannedTransfer(
                    source = source,
                    target = target,
                    replace = conflict && policy == FileConflictPolicy.REPLACE,
                    size = null
                )
            }

            if (cancelled) {
                return@withContext TransferResult(
                    completedPaths = completedPaths,
                    skippedCount = skippedCount,
                    issues = issues,
                    cleanupWarningCount = cleanupWarningCount,
                    sourceDeleteWarningCount = sourceDeleteWarningCount,
                    cancelled = true
                )
            }

            var totalBytes = 0L

            for (index in plannedItems.indices) {
                currentCoroutineContext().ensureActive()

                val item = plannedItems[index]

                // A move inside one volume or one share is a rename: no bytes travel, and
                // walking the tree for a number nothing displays is what made moving a large
                // folder feel like it had stalled. Everything that will have to copy is
                // measured here like a copy, which is what gives the progress a total that
                // no longer grows while the transfer is already running.
                if (operation == TransferOperation.MOVE && willRenameInPlace(item)) {
                    continue
                }

                val statsResult = try {
                    item.source.scan()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    issues += TransferIssue(
                        sourcePath = item.source.pathValue,
                        message = readableMessage(e)
                    )
                    plannedItems[index] = item.copy(invalid = true)
                    continue
                }

                if (statsResult.symbolicLinkCount > 0L) {
                    issues += TransferIssue(
                        sourcePath = item.source.pathValue,
                        message = appContext.getString(R.string.symbolic_links_not_supported)
                    )
                    plannedItems[index] = item.copy(invalid = true)
                    continue
                }

                plannedItems[index] = item.copy(size = statsResult.size)
                totalBytes = safeAdd(totalBytes, statsResult.size)
            }

            val spaceIssue = targetDirectory?.let {
                insufficientSpaceIssue(targetDirectory = it, requiredBytes = totalBytes)
            }

            if (spaceIssue != null) {
                issues += spaceIssue

                return@withContext TransferResult(
                    completedPaths = completedPaths,
                    skippedCount = skippedCount,
                    issues = issues,
                    cleanupWarningCount = cleanupWarningCount,
                    sourceDeleteWarningCount = sourceDeleteWarningCount,
                    cancelled = false
                )
            }

            var processedBytes = 0L
            var transferredBytes = 0L
            val transferStartedNanos = System.nanoTime()
            val executableItems = plannedItems.filterNot { it.invalid }

            for ((index, item) in executableItems.withIndex()) {
                currentCoroutineContext().ensureActive()

                var itemSize = item.size ?: 0L
                var completionRecorded = false

                // Reads the surrounding vars every time it runs rather than capturing them,
                // which is what lets the same lambda serve the announcement before an item
                // and the byte-by-byte updates during it. `totalBytes` in particular grows
                // while the loop runs, when a move falls back to a copy and the size of that
                // item joins the total late.
                val reportProgress: suspend (Long) -> Unit = { copied ->
                    onProgress(
                        progress(
                            item = item,
                            itemIndex = index,
                            totalItems = executableItems.size,
                            bytesProcessed = safeAdd(processedBytes, copied),
                            totalBytes = totalBytes,
                            transferredBytes = safeAdd(transferredBytes, copied),
                            startedNanos = transferStartedNanos,
                            operation = operation
                        )
                    )
                }

                reportProgress(0L)

                try {
                    val result = when (operation) {
                        TransferOperation.COPY -> {
                            transferEngine.copy(
                                source = item.source,
                                target = transferTargetFor(item.target),
                                replace = item.replace,
                                totalBytes = itemSize,
                                onBytesCopied = reportProgress
                            )
                        }

                        TransferOperation.MOVE -> {
                            val fastMove = transferEngine.tryFastMove(
                                source = item.source,
                                target = item.target,
                                replace = item.replace
                            )

                            if (fastMove != null) {
                                fastMove
                            } else {
                                if (item.source.isSymbolicLink()) {
                                    error(appContext.getString(R.string.symbolic_links_not_supported))
                                }

                                // Measured during planning unless the rename was expected to
                                // work. What is left here is a source that changed its mind
                                // between the two, so its size joins the total late — one
                                // step on the bar instead of one per item.
                                val plannedSize = item.size

                                if (plannedSize != null) {
                                    itemSize = plannedSize
                                } else {
                                    val stats = item.source.scan()

                                    if (stats.symbolicLinkCount > 0L) {
                                        error(appContext.getString(R.string.symbolic_links_not_supported))
                                    }

                                    itemSize = stats.size
                                    totalBytes = safeAdd(totalBytes, itemSize)
                                }

                                targetDirectory?.let { directory ->
                                    insufficientSpaceIssue(directory, itemSize)?.let { error(it.message) }
                                }

                                val copyResult = transferEngine.copy(
                                    source = item.source,
                                    target = transferTargetFor(item.target),
                                    replace = item.replace,
                                    totalBytes = itemSize,
                                    onBytesCopied = reportProgress
                                )

                                // The target is already complete at this point. Record it
                                // before deleting the source so cancellation or a cleanup
                                // failure cannot turn a successful copy into an apparent
                                // total failure.
                                completedPaths += item.target.value
                                completionRecorded = true

                                val sourceDeleteFailure = try {
                                    transferEngine.delete(item.source)
                                    null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    e
                                }

                                TransferItemResult(
                                    cleanupWarning = copyResult.cleanupWarning,
                                    bytesTransferred = itemSize,
                                    sourceDeleteFailure = sourceDeleteFailure,
                                    unreadableDirectories = copyResult.unreadableDirectories
                                )
                            }
                        }
                    }

                    if (!completionRecorded) {
                        completedPaths += item.target.value
                    }

                    result.unreadableDirectories.forEach { path ->
                        issues += TransferIssue(
                            sourcePath = path,
                            message = appContext.getString(
                                R.string.unreadable_skipped,
                                File(path).name
                            )
                        )
                    }

                    processedBytes = safeAdd(processedBytes, itemSize)
                    transferredBytes = safeAdd(
                        transferredBytes,
                        result.bytesTransferred
                    )

                    if (result.sourceDeleteFailure != null) {
                        sourceDeleteWarningCount++
                    }

                    if (result.cleanupWarning) {
                        cleanupWarningCount++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    processedBytes = safeAdd(processedBytes, itemSize)
                    issues += TransferIssue(
                        sourcePath = item.source.pathValue,
                        message = readableMessage(e)
                    )
                }
            }

            TransferResult(
                completedPaths = completedPaths,
                skippedCount = skippedCount,
                issues = issues,
                cleanupWarningCount = cleanupWarningCount,
                sourceDeleteWarningCount = sourceDeleteWarningCount,
                cancelled = false
            )
        } catch (e: CancellationException) {
            throw TransferCancelledException(
                partialResult = TransferResult(
                    completedPaths = completedPaths,
                    skippedCount = skippedCount,
                    issues = issues,
                    cleanupWarningCount = cleanupWarningCount,
                    sourceDeleteWarningCount = sourceDeleteWarningCount,
                    cancelled = true
                ),
                cause = e
            )
        }
    }

    /**
     * Whether this item is expected to move without copying, and therefore needs no scan.
     *
     * The two guards in front repeat what [FileTransferEngine.tryFastMove] checks before it
     * even asks the source, so that a prediction and the attempt behind it cannot disagree
     * about the obvious cases. A wrong `true` is not harmful: the fallback in the transfer
     * loop still measures the tree, it just does so late.
     */
    private suspend fun willRenameInPlace(item: PlannedTransfer): Boolean {
        if (item.replace) return false
        if (item.source.location.scheme != item.target.scheme) return false

        return try {
            sources.source(item.target).canMoveWithoutCopy(item.source.location, item.target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unknown: Exception) {
            false
        }
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

    private fun transferTargetFor(path: SourcePath): TransferTarget = if (path.isLocal) {
        TransferTarget.Local(File(path.value))
    } else {
        TransferTarget.Remote(path = path, sources = sources)
    }

    private suspend fun targetExists(path: SourcePath): Boolean = if (path.isLocal) {
        Files.exists(File(path.value).toPath(), LinkOption.NOFOLLOW_LINKS)
    } else {
        runCatching { sources.source(path).stat(path) != null }.getOrDefault(false)
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

    private fun transferSourceFor(path: String): TransferSource {
        val location = SourcePath.parseOrNull(path)

        return if (location == null || location.isLocal) {
            TransferSource.Local(
                file = File(path).toPath().toAbsolutePath().normalize().toFile(),
                fileTreeWalker = fileTreeWalker
            )
        } else {
            TransferSource.Remote(path = location, sources = sources)
        }
    }

    private fun progress(
        item: PlannedTransfer,
        itemIndex: Int,
        totalItems: Int,
        bytesProcessed: Long,
        totalBytes: Long,
        transferredBytes: Long,
        startedNanos: Long,
        operation: TransferOperation
    ): TransferProgress {
        val elapsedNanos = System.nanoTime() - startedNanos
        val speed = if (
            operation == TransferOperation.COPY &&
            elapsedNanos > 0L &&
            transferredBytes > 0L
        ) {
            (
                transferredBytes.toDouble() *
                    1_000_000_000.0 /
                    elapsedNanos.toDouble()
                ).toLong()
        } else {
            null
        }

        return TransferProgress(
            currentItem = itemIndex + 1,
            totalItems = totalItems,
            currentName = item.source.name,
            bytesProcessed = bytesProcessed,
            totalBytes = totalBytes,
            bytesPerSecond = speed
        )
    }

    private fun getAvailableBytes(file: File): Long? {
        val storageManager = appContext.getSystemService(StorageManager::class.java)
        return runCatching {
            val uuid = storageManager.getUuidForPath(file)
            storageManager.getAllocatableBytes(uuid)
        }.getOrElse {
            runCatching { file.usableSpace }.getOrNull()
        }
    }

    private fun insufficientSpaceIssue(targetDirectory: File, requiredBytes: Long): TransferIssue? {
        if (requiredBytes <= 0L) return null

        // usableSpace returns 0 for a full volume as well as for a failed statfs(2), so the two
        // cases are told apart via totalSpace: it is only 0 when the measurement itself is
        // unavailable. A genuine "0 bytes free" has to fail the check instead of skipping it.
        val volumeBytes = runCatching { targetDirectory.totalSpace }.getOrNull() ?: return null
        if (volumeBytes <= 0L) return null

        val usableBytes = getAvailableBytes(targetDirectory) ?: return null

        val requiredWithMargin = safeAdd(requiredBytes, FREE_SPACE_MARGIN_BYTES)
        if (usableBytes >= requiredWithMargin) return null

        return TransferIssue(
            sourcePath = targetDirectory.absolutePath,
            message = appContext.getString(
                R.string.not_enough_space,
                formatBytes(requiredBytes),
                formatBytes(usableBytes)
            )
        )
    }

    private fun requireDirectory(path: String): File {
        val directory = File(path).canonicalFile

        require(directory.exists() && directory.isDirectory) {
            appContext.getString(R.string.target_folder_missing)
        }

        require(directory.canWrite()) {
            appContext.getString(R.string.target_read_only)
        }

        return directory
    }

    private fun readableMessage(error: Throwable): String = when (error) {
        is FileTreeReadException -> appContext.getString(
            R.string.folder_named_read_failed,
            error.directory.name
        )

        is FileTreeCycleException,
        is FileTreeOutsideRootException ->
            appContext.getString(R.string.unsafe_file_tree)

        // A source phrases its failures for the log: "Access denied during WRITE:
        // smb://nas/media". SourceMessages is the single place that turns one into a
        // sentence, and it reads the operation off the exception itself — the one passed
        // here only covers the shapes that carry none.
        is SourceException ->
            sourceMessages.localize(error, SourceOperation.WRITE)

        // Everything the transfer layer raises itself is already localised, e.g. the
        // "already exists" the engine throws when a target appeared underneath it.
        else ->
            error.message
                ?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.error_generic)
    }

    private companion object {
        const val FREE_SPACE_MARGIN_BYTES = 8L * 1024L * 1024L
    }

    private data class PlannedTransfer(
        val source: TransferSource,
        val target: SourcePath,
        val replace: Boolean,
        val size: Long?,
        val invalid: Boolean = false
    )
}
