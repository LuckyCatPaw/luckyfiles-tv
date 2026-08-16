package com.luckycatpaw.luckyfilestv.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.transfer.model.FileConflictPolicy
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.repository.StorageRepository
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferConflictDecision
import com.luckycatpaw.luckyfilestv.data.transfer.TransferCoordinator
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.util.FileUtil
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

class FileDocumentsProvider : DocumentsProvider() {

    private val storageRepository: StorageRepository by lazy {
        StorageRepository(requireNotNull(context).applicationContext)
    }

    private val fileTreeWalker = FileTreeWalker()

    private val transferCoordinator: TransferCoordinator by lazy {
        TransferCoordinator(
            context = requireNotNull(context).applicationContext,
            fileTreeWalker = fileTreeWalker
        )
    }

    private val idResolver = DocumentIdResolver()

    private val securityGuard by lazy {
        DocumentSecurityGuard(
            appContext = requireNotNull(context).applicationContext,
            idResolver = idResolver
        )
    }

    private val cursorBuilder by lazy {
        DocumentCursorBuilder(
            idResolver = idResolver,
            securityGuard = securityGuard
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        currentStorages().forEach { storage ->
            val root = runCatching { File(storage.path).canonicalFile }.getOrNull() ?: return@forEach
            if (root.exists() && root.isDirectory) {
                cursorBuilder.addRootRow(cursor, storage, root)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val storageSnapshot = managedStorageSnapshot()
        cursorBuilder.addDocumentRow(
            cursor = cursor,
            canonicalFile = securityGuard.requireManagedFile(documentId, storageSnapshot),
            storageSnapshot = storageSnapshot
        )
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val storageSnapshot = managedStorageSnapshot()
        val parent = securityGuard.requireManagedFile(parentDocumentId, storageSnapshot)
        securityGuard.requireDirectory(parent)

        parent.listFiles()?.mapNotNull { child ->
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!securityGuard.isInsideManagedStoragePath(canonical.path, storageSnapshot) ||
                securityGuard.isSafRestrictedPath(canonical.path, storageSnapshot)
            ) {
                return@mapNotNull null
            }
            canonical
        }?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )?.forEach { file ->
            cursorBuilder.addDocumentRow(cursor, file, storageSnapshot)
        }

        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = securityGuard.requireManagedFile(documentId, managedStorageSnapshot())

        if (!file.exists() || !file.isFile) {
            throw FileNotFoundException(file.absolutePath)
        }

        val wantsWrite = mode.contains('w') || mode.contains('+')
        if (wantsWrite && !file.canWrite()) {
            throw FileNotFoundException(
                requireNotNull(context).getString(R.string.provider_file_read_only, file.absolutePath)
            )
        }

        return try {
            signal?.throwIfCanceled()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        } catch (e: Exception) {
            throw FileNotFoundException(
                requireNotNull(context).getString(R.string.provider_cannot_open, file.absolutePath, e.message ?: "")
            )
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val storageSnapshot = managedStorageSnapshot()
        val parent = securityGuard.requireManagedFile(parentDocumentId, storageSnapshot)
        securityGuard.requireDirectory(parent)
        securityGuard.requireWritable(parent)

        val safeName = sanitizeFileName(displayName)
        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val destination = FileUtil.createUniqueDestination(parent, safeName, isDir)

        val created = if (isDir) destination.mkdir() else destination.createNewFile()
        if (!created) {
            throw FileNotFoundException(
                requireNotNull(context).getString(R.string.provider_could_not_create, destination.absolutePath)
            )
        }

        notifyDirectoryChanged(parent, storageSnapshot)
        return idResolver.toDocumentId(destination)
    }

    override fun deleteDocument(documentId: String) {
        val storageSnapshot = managedStorageSnapshot()
        val file = securityGuard.requireManagedFile(documentId, storageSnapshot)
        securityGuard.requireNotRoot(file, storageSnapshot)

        val parent = file.parentFile?.canonicalFile
            ?: throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_document_no_parent))

        securityGuard.requireWritable(parent)

        val deleted = file.deleteRecursively()
        if (!deleted) {
            throw FileNotFoundException(
                requireNotNull(context).getString(R.string.provider_could_not_delete, file.absolutePath)
            )
        }

        notifyDirectoryChanged(parent, storageSnapshot)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val storageSnapshot = managedStorageSnapshot()
        val source = securityGuard.requireManagedFile(documentId, storageSnapshot)
        securityGuard.requireNotRoot(source, storageSnapshot)

        val parent = source.parentFile?.canonicalFile
            ?: throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_document_no_parent))

        securityGuard.requireWritable(parent)
        val safeName = sanitizeFileName(displayName)

        if (safeName == source.name) return documentId

        val destination = File(parent, safeName).canonicalFile
        if (destination.exists()) {
            throw FileNotFoundException(
                requireNotNull(context).getString(R.string.provider_document_exists, safeName)
            )
        }

        if (!source.renameTo(destination)) {
            throw FileNotFoundException(
                requireNotNull(context).getString(R.string.provider_could_not_rename, source.name)
            )
        }

        notifyDirectoryChanged(parent, storageSnapshot)
        return idResolver.toDocumentId(destination)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val storageSnapshot = managedStorageSnapshot()
        val source = securityGuard.requireManagedFile(sourceDocumentId, storageSnapshot)
        val targetParent = securityGuard.requireManagedFile(targetParentDocumentId, storageSnapshot)

        securityGuard.requireDirectory(targetParent)
        securityGuard.requireWritable(targetParent)

        if (securityGuard.isRootFile(source, storageSnapshot)) {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_storage_root_copy))
        }

        if (source.isDirectory && securityGuard.isSameOrChild(source, targetParent)) {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_copy_into_self))
        }

        val destination = transferDocument(source, targetParent, TransferOperation.COPY)
        notifyDirectoryChanged(targetParent, storageSnapshot)
        return idResolver.toDocumentId(destination)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        val storageSnapshot = managedStorageSnapshot()
        val source = securityGuard.requireManagedFile(sourceDocumentId, storageSnapshot)
        val sourceParent = securityGuard.requireManagedFile(sourceParentDocumentId, storageSnapshot)
        val targetParent = securityGuard.requireManagedFile(targetParentDocumentId, storageSnapshot)

        securityGuard.requireNotRoot(source, storageSnapshot)
        securityGuard.requireDirectory(sourceParent)
        securityGuard.requireDirectory(targetParent)
        securityGuard.requireWritable(sourceParent)
        securityGuard.requireWritable(targetParent)

        val actualParent = source.parentFile?.canonicalFile
            ?: throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_source_no_parent))

        if (actualParent != sourceParent.canonicalFile) {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_source_parent_mismatch))
        }

        if (actualParent == targetParent.canonicalFile) return sourceDocumentId

        if (source.isDirectory && securityGuard.isSameOrChild(source, targetParent)) {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_move_into_self))
        }

        val destination = transferDocument(source, targetParent, TransferOperation.MOVE)
        notifyDirectoryChanged(sourceParent, storageSnapshot)
        notifyDirectoryChanged(targetParent, storageSnapshot)
        return idResolver.toDocumentId(destination)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        val storageSnapshot = managedStorageSnapshot()
        val file = securityGuard.requireManagedFile(documentId, storageSnapshot)
        val parent = securityGuard.requireManagedFile(parentDocumentId, storageSnapshot)

        val actualParent = file.parentFile?.canonicalFile
            ?: throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_document_no_parent))

        if (actualParent != parent.canonicalFile) {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_parent_mismatch))
        }

        deleteDocument(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val storageSnapshot = managedStorageSnapshot()
            val parent = securityGuard.requireManagedFile(parentDocumentId, storageSnapshot)
            val child = securityGuard.requireManagedFile(documentId, storageSnapshot)
            (parent != child) && securityGuard.isSameOrChild(parent, child)
        } catch (_: Exception) {
            false
        }
    }

    private fun transferDocument(source: File, targetParent: File, operation: TransferOperation): File {
        val result = runBlocking {
            transferCoordinator.execute(
                sourcePaths = listOf(source.absolutePath),
                targetDirectoryPath = targetParent.absolutePath,
                operation = operation,
                onConflict = {
                    TransferConflictDecision(
                        policy = FileConflictPolicy.KEEP_BOTH,
                        applyToAll = true,
                        cancelled = false
                    )
                },
                onProgress = {}
            )
        }

        val destination = result.completedPaths.singleOrNull()?.let(::File)
        if (destination == null || result.issues.isNotEmpty()) {
            val message = result.issues.firstOrNull()?.message
                ?: requireNotNull(context).getString(R.string.provider_could_not_copy, source.absolutePath)
            throw FileNotFoundException(message)
        }
        return destination
    }

    private fun currentStorages(): List<BrowserItem.Storage> =
        storageRepository.getStoragesSync().filter { storage ->
            runCatching {
                val file = File(storage.path).canonicalFile
                file.exists() && file.isDirectory
            }.getOrDefault(false)
        }

    private fun managedStorageSnapshot(): DocumentIdResolver.ManagedStorageSnapshot {
        val rootEntries = currentStorages().mapNotNull { storage ->
            runCatching { File(storage.path).canonicalFile to storage.name }.getOrNull()
        }
        return DocumentIdResolver.ManagedStorageSnapshot(
            roots = rootEntries.map { it.first },
            namesByRootPath = rootEntries.associateBy({ it.first.path }, { it.second })
        )
    }

    private fun sanitizeFileName(name: String): String {
        val clean = FileUtil.sanitizeFileName(name)
        if (clean == "unnamed") {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.provider_invalid_file_name))
        }
        return clean
    }

    private fun notifyDirectoryChanged(directory: File, storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot) {
        if (!securityGuard.isInsideManagedStorage(directory, storageSnapshot)) return
        runCatching {
            val ctx = requireNotNull(context)
            val authority = "${ctx.packageName}.documents"
            val uri = DocumentsContract.buildChildDocumentsUri(authority, idResolver.toDocumentId(directory))
            ctx.contentResolver.notifyChange(uri, null)
        }
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
