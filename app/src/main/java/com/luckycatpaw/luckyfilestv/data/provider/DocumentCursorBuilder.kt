package com.luckycatpaw.luckyfilestv.data.provider

import android.database.MatrixCursor
import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File

internal class DocumentCursorBuilder(
    private val idResolver: DocumentIdResolver,
    private val securityGuard: DocumentSecurityGuard
) {
    fun addRootRow(
        cursor: MatrixCursor,
        storage: BrowserItem.Storage,
        root: File
    ) {
        var flags = DocumentsContract.Root.FLAG_LOCAL_ONLY or
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD

        if (root.canWrite()) {
            flags = flags or DocumentsContract.Root.FLAG_SUPPORTS_CREATE
        }

        addRow(
            cursor,
            mapOf(
                DocumentsContract.Root.COLUMN_ROOT_ID to idResolver.toDocumentId(root),
                DocumentsContract.Root.COLUMN_FLAGS to flags,
                DocumentsContract.Root.COLUMN_TITLE to storage.name,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID to idResolver.toDocumentId(root),
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES to root.freeSpace,
                DocumentsContract.Root.COLUMN_CAPACITY_BYTES to root.totalSpace
            )
        )
    }

    fun addDocumentRow(
        cursor: MatrixCursor,
        canonicalFile: File,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ) {
        val documentId = idResolver.toDocumentId(canonicalFile)
        val isDirectory = canonicalFile.isDirectory
        val isRoot = securityGuard.isRootFile(canonicalFile, storageSnapshot)
        var flags = 0

        if (isDirectory && canonicalFile.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }

        if (!isDirectory && canonicalFile.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }

        if (isDirectory && securityGuard.blocksOpenDocumentTree(documentId, storageSnapshot)) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
        }

        if (!isRoot) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_COPY

            val parentWritable = canonicalFile.parentFile?.canWrite() ?: false
            if (parentWritable) {
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
            }
        }

        val mimeType = if (isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            MimeTypes.forFileName(canonicalFile.name)
        }

        val values = mutableMapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to displayName(canonicalFile, storageSnapshot),
            DocumentsContract.Document.COLUMN_SIZE to canonicalFile.length(),
            DocumentsContract.Document.COLUMN_MIME_TYPE to mimeType,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to canonicalFile.lastModified(),
            DocumentsContract.Document.COLUMN_FLAGS to flags
        )

        addRow(cursor, values)
    }

    private fun addRow(cursor: MatrixCursor, values: Map<String, Any?>) {
        val row = cursor.newRow()
        for ((column, value) in values) {
            row.add(column, value)
        }
    }

    private fun displayName(
        file: File,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): String {
        return storageSnapshot.namesByRootPath[file.path] ?: file.name
    }
}
