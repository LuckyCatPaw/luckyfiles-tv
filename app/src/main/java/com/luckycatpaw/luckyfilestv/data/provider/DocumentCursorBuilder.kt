package com.luckycatpaw.luckyfilestv.data.provider

import android.database.MatrixCursor
import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal class DocumentCursorBuilder(
    private val idResolver: DocumentIdResolver,
    private val securityGuard: DocumentSecurityGuard
) {

    internal data class DocumentEntry(
        val file: File,
        val canonicalPath: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long?,
        val lastModified: Long
    )

    fun entryFor(canonicalFile: File): DocumentEntry {
        val attributes = runCatching {
            Files.readAttributes(canonicalFile.toPath(), BasicFileAttributes::class.java)
        }.getOrNull()

        val isDirectory = attributes?.isDirectory ?: canonicalFile.isDirectory

        return DocumentEntry(
            file = canonicalFile,
            canonicalPath = canonicalFile.path,
            name = canonicalFile.name,
            isDirectory = isDirectory,
            size = if (isDirectory) null else attributes?.size() ?: canonicalFile.length(),
            lastModified = attributes?.lastModifiedTime()?.toMillis() ?: canonicalFile.lastModified()
        )
    }

    fun addRootRow(cursor: MatrixCursor, storage: BrowserItem.Storage, root: File) {
        var flags = DocumentsContract.Root.FLAG_LOCAL_ONLY or
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD

        if (root.canWrite()) {
            flags = flags or DocumentsContract.Root.FLAG_SUPPORTS_CREATE
        }

        val documentId = idResolver.toDocumentIdFromCanonicalPath(root.path)

        addRow(
            cursor,
            mapOf(
                DocumentsContract.Root.COLUMN_ROOT_ID to documentId,
                DocumentsContract.Root.COLUMN_FLAGS to flags,
                DocumentsContract.Root.COLUMN_TITLE to storage.name,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID to documentId,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES to root.freeSpace,
                DocumentsContract.Root.COLUMN_CAPACITY_BYTES to root.totalSpace
            )
        )
    }

    fun addDocumentRow(
        cursor: MatrixCursor,
        entry: DocumentEntry,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot,
        parentWritable: Boolean
    ) {
        val documentId = idResolver.toDocumentIdFromCanonicalPath(entry.canonicalPath)
        val isRoot = securityGuard.isRootPath(entry.canonicalPath, storageSnapshot)
        val writable = entry.file.canWrite()

        var flags = when {
            entry.isDirectory && writable -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            !entry.isDirectory && writable -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            else -> 0
        }

        if (
            entry.isDirectory &&
            securityGuard.blocksOpenDocumentTree(entry.canonicalPath, storageSnapshot)
        ) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
        }

        if (!isRoot) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_COPY

            if (parentWritable) {
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
            }
        }

        val mimeType = if (entry.isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            MimeTypes.forFileName(entry.name)
        }

        addRow(
            cursor,
            mapOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to displayName(entry, storageSnapshot),
                DocumentsContract.Document.COLUMN_SIZE to entry.size,
                DocumentsContract.Document.COLUMN_MIME_TYPE to mimeType,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to entry.lastModified,
                DocumentsContract.Document.COLUMN_FLAGS to flags
            )
        )
    }

    private fun addRow(cursor: MatrixCursor, values: Map<String, Any?>) {
        val available = cursor.columnNames.toHashSet()
        val row = cursor.newRow()
        for ((column, value) in values) {
            if (column in available) {
                row.add(column, value)
            }
        }
    }

    private fun displayName(entry: DocumentEntry, storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot): String =
        storageSnapshot.namesByRootPath[entry.canonicalPath] ?: entry.name
}
