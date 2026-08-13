package com.luckycatpaw.luckyfilestv.data.provider.model

import android.net.Uri
import android.provider.DocumentsContract

data class DocumentProviderInfo(
    val packageName: String,
    val authority: String,
    val label: String
)

data class DocumentRootInfo(
    val packageName: String,
    val authority: String,
    val rootId: String,
    val documentId: String,
    val title: String,
    val summary: String?,
    val flags: Int,
    val iconResId: Int,
    val mimeTypes: List<String>?
) {
    val documentUri: Uri
        get() = DocumentsContract.buildDocumentUri(authority, documentId)

    val isLocalOnly: Boolean
        get() = flags and DocumentsContract.Root.FLAG_LOCAL_ONLY != 0

    val supportsCreate: Boolean
        get() = flags and DocumentsContract.Root.FLAG_SUPPORTS_CREATE != 0

    val supportsRecents: Boolean
        get() = flags and DocumentsContract.Root.FLAG_SUPPORTS_RECENTS != 0

    val supportsSearch: Boolean
        get() = flags and DocumentsContract.Root.FLAG_SUPPORTS_SEARCH != 0

    val supportsIsChild: Boolean
        get() = flags and DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD != 0

    val isEmpty: Boolean
        get() = flags and DocumentsContract.Root.FLAG_EMPTY != 0
}

data class ProviderDocumentInfo(
    val authority: String,
    val documentId: String,
    val parentDocumentId: String?,
    val displayName: String,
    val mimeType: String,
    val flags: Int,
    val iconResId: Int,
    val size: Long?,
    val lastModified: Long?
) {
    val uri: Uri
        get() = DocumentsContract.buildDocumentUri(authority, documentId)

    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    val isVirtual: Boolean
        get() = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0

    val supportsThumbnail: Boolean
        get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL != 0

    val supportsCreate: Boolean
        get() = flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0

    val blocksOpenDocumentTree: Boolean
        get() = flags and DocumentsContract.Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE != 0
}

data class DocumentRootsResult(
    val roots: List<DocumentRootInfo>,
    val errors: List<DocumentProviderError>
)

data class ProviderChildrenResult(
    val documents: List<ProviderDocumentInfo>,
    val loading: Boolean,
    val info: String?,
    val error: String?
)

data class ProviderDocumentPath(
    val rootId: String?,
    val documentIds: List<String>
)

data class DocumentProviderError(
    val authority: String,
    val message: String
)
