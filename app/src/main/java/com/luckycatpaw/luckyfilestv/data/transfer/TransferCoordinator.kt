package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import android.os.storage.StorageManager
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeCycleException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeOutsideRootException
import com.luckycatpaw.luckyfilestv.data.common.model.FileTreeReadException
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
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
    private val transferEngine = FileTransferEngine(
        context = appContext,
        fileTreeWalker = fileTreeWalker
    )

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
            // Writing to a share is not implemented yet, so a transfer always ends on a
            // local volume. Reading may come from anywhere.
            if (SourcePath.parseOrNull(targetDirectoryPath)?.isLocal != true) {
                return@withContext TransferResult(
                    completedPaths = completedPaths,
                    skippedCount = skippedCount,
                    issues = listOf(
                        TransferIssue(
                            sourcePath = targetDirectoryPath,
                            message = appContext.getString(R.string.transfer_to_share_unsupported)
                        )
                    ),
                    cleanupWarningCount = cleanupWarningCount,
                    sourceDeleteWarningCount = sourceDeleteWarningCount,
                    cancelled = false
                )
            }

            val targetDirectory = requireDirectory(targetDirectoryPath)
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

                // Moving off a share would have to delete on the server, which the SMB
                // source cannot do yet. Copying works, so say which of the two it is.
                if (operation == TransferOperation.MOVE && source !is TransferSource.Local) {
                    issues += TransferIssue(
                        sourcePath = source.pathValue,
                        message = appContext.getString(R.string.transfer_move_from_share_unsupported)
                    )
                    continue
                }

                if (!source.exists()) {
                    issues += TransferIssue(
                        sourcePath = source.pathValue,
                        message = appContext.getString(R.string.source_missing)
                    )
                    continue
                }

                val localSource = (source as? TransferSource.Local)?.file
                val sourceIsSymbolicLink = localSource != null &&
                    Files.isSymbolicLink(localSource.toPath())

                if (sourceIsSymbolicLink && operation == TransferOperation.COPY) {
                    issues += TransferIssue(
                        sourcePath = source.pathValue,
                        message = appContext.getString(R.string.symbolic_links_not_supported)
                    )
                    continue
                }

                val sourceParent = localSource?.parentFile?.canonicalFile

                if (
                    operation == TransferOperation.MOVE &&
                    sourceParent == targetDirectory
                ) {
                    completedPaths += source.pathValue
                    continue
                }

                val sourceIsDirectory = source.isDirectory()

                if (
                    !sourceIsSymbolicLink &&
                    sourceIsDirectory &&
                    localSource != null &&
                    FileUtil.isSameOrChild(localSource, targetDirectory)
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

                val directTarget = File(targetDirectory, source.name).absoluteFile
                val sameTarget = directTarget == localSource
                val conflict = (
                    Files.exists(directTarget.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        directTarget.absolutePath in reservedTargets
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
                            targetDirectory = targetDirectory.absolutePath,
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

                    else -> FileUtil.createUniqueDestination(
                        parent = targetDirectory,
                        requestedName = source.name,
                        isDirectory = sourceIsDirectory,
                        reservedTargets = reservedTargets
                    )
                }

                reservedTargets += target.absolutePath

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

                if (operation == TransferOperation.MOVE) {
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

            val spaceIssue = insufficientSpaceIssue(
                targetDirectory = targetDirectory,
                requiredBytes = totalBytes
            )

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

                onProgress(
                    progress(
                        item = item,
                        itemIndex = index,
                        totalItems = executableItems.size,
                        bytesProcessed = processedBytes,
                        totalBytes = totalBytes,
                        transferredBytes = transferredBytes,
                        startedNanos = transferStartedNanos,
                        operation = operation
                    )
                )

                try {
                    val result = when (operation) {
                        TransferOperation.COPY -> {
                            transferEngine.copy(
                                source = item.source,
                                target = item.target,
                                replace = item.replace,
                                totalBytes = itemSize,
                                onBytesCopied = { copied ->
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
                            )
                        }

                        TransferOperation.MOVE -> {
                            val movableSource = (item.source as TransferSource.Local).file

                            val fastMove = transferEngine.tryFastMove(
                                source = movableSource,
                                target = item.target,
                                replace = item.replace
                            )

                            if (fastMove != null) {
                                fastMove
                            } else {
                                if (Files.isSymbolicLink(movableSource.toPath())) {
                                    error(appContext.getString(R.string.symbolic_links_not_supported))
                                }

                                val stats = item.source.scan()

                                if (stats.symbolicLinkCount > 0L) {
                                    error(appContext.getString(R.string.symbolic_links_not_supported))
                                }

                                itemSize = stats.size
                                totalBytes = safeAdd(totalBytes, itemSize)

                                insufficientSpaceIssue(targetDirectory, itemSize)?.let {
                                    error(it.message)
                                }

                                val copyResult = transferEngine.copy(
                                    source = item.source,
                                    target = item.target,
                                    replace = item.replace,
                                    totalBytes = itemSize,
                                    onBytesCopied = { copied ->
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
                                )

                                // The target is already complete at this point. Record it
                                // before deleting the source so cancellation or a cleanup
                                // failure cannot turn a successful copy into an apparent
                                // total failure.
                                completedPaths += item.target.absolutePath
                                completionRecorded = true

                                val sourceDeleteFailure = try {
                                    transferEngine.delete(movableSource)
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
                        completedPaths += item.target.absolutePath
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

        else ->
            error.message
                ?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.error_generic)
    }

    private fun safeAdd(first: Long, second: Long): Long = if (Long.MAX_VALUE - first < second) {
        Long.MAX_VALUE
    } else {
        first + second
    }

    private companion object {
        const val FREE_SPACE_MARGIN_BYTES = 8L * 1024L * 1024L
    }

    private data class PlannedTransfer(
        val source: TransferSource,
        val target: File,
        val replace: Boolean,
        val size: Long?,
        val invalid: Boolean = false
    )
}
