package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeCycleException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeOutsideRootException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.source.AndroidSourceMessages
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferCancelledException
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflict
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferProgress
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferResult
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val sourceMessages = AndroidSourceMessages(appContext)
    private val messages: TransferMessages = AndroidTransferMessages(appContext)
    private val planner = TransferPlanner(messages = messages, targetExists = ::targetExists)
    private val measurer = TransferMeasurer(
        messages = messages,
        willRenameInPlace = ::willRenameInPlace,
        describeFailure = ::readableMessage
    )
    private val freeSpace = FreeSpaceCheck(messages = messages, availableBytes = AndroidAvailableBytes(appContext))
    private val transferEngine: TransferEngine by lazy {
        FileTransferEngine(
            context = appContext,
            fileTreeWalker = fileTreeWalker,
            sources = sources
        )
    }
    private val runner by lazy {
        TransferRunner(
            engine = transferEngine,
            messages = messages,
            freeSpace = freeSpace,
            targetFor = ::transferTargetFor,
            describeFailure = ::readableMessage
        )
    }

    suspend fun execute(
        sourcePaths: List<String>,
        targetDirectoryPath: String,
        operation: TransferOperation,
        onConflict: suspend (TransferConflict) -> TransferConflictDecision,
        onProgress: suspend (TransferProgress) -> Unit
    ): TransferResult = withContext(ioDispatcher) {
        val tally = TransferTally()

        try {
            val targetLocation = SourcePath.parse(targetDirectoryPath)
            val targetDirectory = if (targetLocation.isLocal) requireDirectory(targetDirectoryPath) else null

            val plan = planner.plan(
                request = PlanRequest(
                    sources = sourcePaths.map(::transferSourceFor),
                    targetLocation = targetLocation,
                    localTargetDirectory = targetDirectory,
                    operation = operation
                ),
                tally = tally,
                onConflict = onConflict
            )

            if (plan.cancelled) return@withContext tally.result(cancelled = true)

            val measurement = measurer.measure(plan.items, operation, tally)
            val plannedItems = measurement.items
            val totalBytes = measurement.totalBytes

            val spaceIssue = targetDirectory?.let {
                freeSpace.issueFor(targetDirectory = it, requiredBytes = totalBytes)
            }

            if (spaceIssue != null) {
                tally.failed(spaceIssue.sourcePath, spaceIssue.message)

                return@withContext tally.result(cancelled = false)
            }

            runner.run(
                request = RunRequest(
                    items = plannedItems.filterNot { it.invalid },
                    operation = operation,
                    totalBytes = totalBytes,
                    targetDirectory = targetDirectory
                ),
                tally = tally,
                onProgress = onProgress
            )

            tally.result(cancelled = false)
        } catch (e: CancellationException) {
            throw TransferCancelledException(partialResult = tally.result(cancelled = true), cause = e)
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
        } catch (_: Exception) {
            false
        }
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

    private fun requireDirectory(path: String): File {
        val directory = File(path).canonicalFile

        require(directory.exists() && directory.isDirectory) { messages.targetFolderMissing() }

        require(directory.canWrite()) { messages.targetReadOnly() }

        return directory
    }

    private fun readableMessage(error: Throwable): String = when (error) {
        is FileTreeReadException -> messages.folderReadFailed(error.directory.name)

        is FileTreeCycleException,
        is FileTreeOutsideRootException ->
            messages.unsafeFileTree()

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
                ?: messages.generic()
    }
}
