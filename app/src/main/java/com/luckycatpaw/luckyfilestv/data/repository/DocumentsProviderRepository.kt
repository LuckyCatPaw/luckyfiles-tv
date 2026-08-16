package com.luckycatpaw.luckyfilestv.data.repository

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.ProviderCallRunner
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentProviderError
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentProviderInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootsResult
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderChildrenResult
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentPath
import com.luckycatpaw.luckyfilestv.util.FileUtil
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import com.luckycatpaw.luckyfilestv.util.int
import com.luckycatpaw.luckyfilestv.util.longOrNull
import com.luckycatpaw.luckyfilestv.util.requiredString
import com.luckycatpaw.luckyfilestv.util.string
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class DocumentsProviderRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val packageManager = appContext.packageManager

    fun hasSystemDocumentAccess(): Boolean = appContext.checkSelfPermission(
        Manifest.permission.MANAGE_DOCUMENTS
    ) == PackageManager.PERMISSION_GRANTED

    private suspend fun discoverProviders(includeSelf: Boolean): List<DocumentProviderInfo> =
        withContext(Dispatchers.IO) {
            val intent = Intent(DocumentsContract.PROVIDER_INTERFACE)

            val providers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentContentProviders(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentContentProviders(intent, 0)
            }

            providers.flatMap { resolveInfo ->
                val provider = resolveInfo.providerInfo ?: return@flatMap emptyList()

                if (!includeSelf && provider.packageName == appContext.packageName) {
                    return@flatMap emptyList()
                }

                val label = runCatching {
                    provider.loadLabel(packageManager).toString().trim()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: provider.packageName

                provider.authority
                    ?.split(';')
                    ?.mapNotNull { authority ->
                        authority.trim().takeIf { it.isNotBlank() }?.let {
                            DocumentProviderInfo(
                                packageName = provider.packageName,
                                authority = it,
                                label = label
                            )
                        }
                    }
                    .orEmpty()
            }.distinctBy {
                it.authority
            }.sortedWith(
                Comparator { first, second ->
                    val labelResult = String.CASE_INSENSITIVE_ORDER.compare(
                        first.label,
                        second.label
                    )

                    if (labelResult != 0) {
                        labelResult
                    } else {
                        first.authority.compareTo(second.authority, ignoreCase = true)
                    }
                }
            )
        }

    suspend fun queryRoots(
        acceptedMimeTypes: List<String> = listOf(MimeTypes.ANY),
        localOnly: Boolean = false,
        requireCreate: Boolean = false,
        excludeSelf: Boolean = false
    ): DocumentRootsResult = coroutineScope {
        val providers = discoverProviders(includeSelf = !excludeSelf)
        val semaphore = Semaphore(MAX_PARALLEL_ROOT_QUERIES)
        val outcomes = providers.map { provider ->
            async {
                semaphore.withPermit {
                    queryProviderRootsWithTimeout(
                        provider = provider,
                        acceptedMimeTypes = acceptedMimeTypes,
                        localOnly = localOnly,
                        requireCreate = requireCreate
                    )
                }
            }
        }.awaitAll()
        val roots = outcomes.flatMap { it.roots }
        val errors = outcomes.mapNotNull { it.error }

        DocumentRootsResult(
            roots = roots
                .distinctBy { "${it.authority}:${it.rootId}" }
                .sortedWith(
                    Comparator { first, second ->
                        val titleResult = String.CASE_INSENSITIVE_ORDER.compare(
                            first.title,
                            second.title
                        )

                        if (titleResult != 0) {
                            titleResult
                        } else {
                            String.CASE_INSENSITIVE_ORDER.compare(
                                first.summary ?: "",
                                second.summary ?: ""
                            )
                        }
                    }
                ),
            errors = errors
        )
    }

    private suspend fun queryProviderRootsWithTimeout(
        provider: DocumentProviderInfo,
        acceptedMimeTypes: List<String>,
        localOnly: Boolean,
        requireCreate: Boolean
    ): RootQueryOutcome = try {
        val roots = withTimeoutOrNull(PROVIDER_QUERY_TIMEOUT) {
            ProviderCallRunner.run { signal ->
                queryProviderRoots(
                    provider = provider,
                    acceptedMimeTypes = acceptedMimeTypes,
                    localOnly = localOnly,
                    requireCreate = requireCreate,
                    cancellationSignal = signal
                )
            }
        }

        if (roots == null) {
            RootQueryOutcome(
                error = DocumentProviderError(
                    authority = provider.authority,
                    message = appContext.getString(R.string.provider_query_timeout, provider.label)
                )
            )
        } else {
            RootQueryOutcome(roots = roots)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        RootQueryOutcome(
            error = DocumentProviderError(
                authority = provider.authority,
                message = appContext.getString(R.string.provider_no_manage_access, provider.label)
            )
        )
    } catch (e: Exception) {
        RootQueryOutcome(
            error = DocumentProviderError(
                authority = provider.authority,
                message = e.message ?: appContext.getString(R.string.provider_read_failed)
            )
        )
    }

    suspend fun queryRootDocument(root: DocumentRootInfo): Result<ProviderDocumentInfo> = queryDocument(
        authority = root.authority,
        documentId = root.documentId
    )

    suspend fun queryDocument(
        authority: String,
        documentId: String,
        parentDocumentId: String? = null
    ): Result<ProviderDocumentInfo> = FileUtil.runCancellable {
        ProviderCallRunner.run { signal ->
            val uri = DocumentsContract.buildDocumentUri(authority, documentId)

            resolver.query(
                uri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null,
                signal
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    error(appContext.getString(R.string.document_not_found))
                }

                documentFromCursor(
                    cursor = cursor,
                    authority = authority,
                    parentDocumentId = parentDocumentId
                )
            } ?: error(appContext.getString(R.string.provider_no_document))
        }
    }

    suspend fun findDocumentPath(uri: Uri): Result<ProviderDocumentPath> = FileUtil.runCancellable {
        ProviderCallRunner.run { _ ->
            val path = DocumentsContract.findDocumentPath(
                resolver,
                uri
            ) ?: error(appContext.getString(R.string.document_not_found))

            ProviderDocumentPath(
                authority = uri.authority ?: error("No authority in URI"),
                rootId = path.rootId,
                documentIds = path.path
            )
        }
    }

    suspend fun queryChildren(
        authority: String,
        parentDocumentId: String,
        acceptedMimeTypes: List<String> = listOf(MimeTypes.ANY),
        directoriesOnly: Boolean = false,
        openableOnly: Boolean = false,
        cancellationSignal: CancellationSignal? = null
    ): Result<ProviderChildrenResult> {
        val uri = DocumentsContract.buildChildDocumentsUri(
            authority,
            parentDocumentId
        )

        return queryDocumentList(
            uri = uri,
            authority = authority,
            parentDocumentId = parentDocumentId,
            acceptedMimeTypes = acceptedMimeTypes,
            directoriesOnly = directoriesOnly,
            openableOnly = openableOnly,
            sortMode = ListSortMode.CHILDREN,
            cancellationSignal = cancellationSignal
        )
    }

    suspend fun searchDocuments(
        root: DocumentRootInfo,
        query: String,
        acceptedMimeTypes: List<String> = listOf(MimeTypes.ANY),
        directoriesOnly: Boolean = false,
        openableOnly: Boolean = false,
        cancellationSignal: CancellationSignal? = null
    ): Result<ProviderChildrenResult> {
        if (!root.supportsSearch) {
            return Result.success(
                ProviderChildrenResult(
                    documents = emptyList(),
                    loading = false,
                    info = null,
                    error = null
                )
            )
        }

        val uri = DocumentsContract.buildSearchDocumentsUri(
            root.authority,
            root.rootId,
            query
        )

        return queryDocumentList(
            uri = uri,
            authority = root.authority,
            parentDocumentId = null,
            acceptedMimeTypes = acceptedMimeTypes,
            directoriesOnly = directoriesOnly,
            openableOnly = openableOnly,
            sortMode = ListSortMode.PROVIDER_ORDER,
            cancellationSignal = cancellationSignal
        )
    }

    suspend fun queryRecentDocuments(
        root: DocumentRootInfo,
        acceptedMimeTypes: List<String> = listOf(MimeTypes.ANY),
        openableOnly: Boolean = false,
        cancellationSignal: CancellationSignal? = null
    ): Result<ProviderChildrenResult> {
        if (!root.supportsRecents) {
            return Result.success(
                ProviderChildrenResult(
                    documents = emptyList(),
                    loading = false,
                    info = null,
                    error = null
                )
            )
        }

        val uri = DocumentsContract.buildRecentDocumentsUri(
            root.authority,
            root.rootId
        )

        return queryDocumentList(
            uri = uri,
            authority = root.authority,
            parentDocumentId = null,
            acceptedMimeTypes = acceptedMimeTypes,
            directoriesOnly = false,
            openableOnly = openableOnly,
            sortMode = ListSortMode.RECENT,
            cancellationSignal = cancellationSignal
        )
    }

    suspend fun createDocument(
        authority: String,
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): Result<ProviderDocumentInfo> = FileUtil.runCancellable {
        ProviderCallRunner.run { _ ->
            val parentUri = DocumentsContract.buildDocumentUri(
                authority,
                parentDocumentId
            )

            val createdUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                mimeType,
                displayName
            ) ?: error(appContext.getString(R.string.document_create_failed))

            documentFromUriSync(
                authority = authority,
                uri = createdUri
            )
        }
    }

    private fun documentFromUriSync(authority: String, uri: Uri): ProviderDocumentInfo {
        val cursor = resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)
            ?: error(appContext.getString(R.string.provider_no_document))

        return cursor.use {
            if (!it.moveToFirst()) {
                error(appContext.getString(R.string.document_not_found))
            }

            documentFromCursor(
                cursor = it,
                authority = authority,
                parentDocumentId = null
            )
        }
    }

    suspend fun createDirectory(
        authority: String,
        parentDocumentId: String,
        displayName: String
    ): Result<ProviderDocumentInfo> = createDocument(
        authority = authority,
        parentDocumentId = parentDocumentId,
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        displayName = displayName
    )

    private suspend fun queryDocumentList(
        uri: Uri,
        authority: String,
        parentDocumentId: String?,
        acceptedMimeTypes: List<String>,
        directoriesOnly: Boolean,
        openableOnly: Boolean,
        sortMode: ListSortMode,
        cancellationSignal: CancellationSignal? = null
    ): Result<ProviderChildrenResult> = FileUtil.runCancellable {
        ProviderCallRunner.run(cancellationSignal) { signal ->
            val documents = mutableListOf<ProviderDocumentInfo>()
            val mimeMatcher = MimeTypes.matcher(acceptedMimeTypes)

            resolver.query(
                uri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null,
                signal
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val document = documentFromCursor(
                        cursor = cursor,
                        authority = authority,
                        parentDocumentId = parentDocumentId
                    )

                    if (
                        shouldShowDocument(
                            document = document,
                            mimeMatcher = mimeMatcher,
                            directoriesOnly = directoriesOnly,
                            openableOnly = openableOnly
                        )
                    ) {
                        documents += document
                    }
                }

                val sorted = when (sortMode) {
                    ListSortMode.PROVIDER_ORDER -> documents

                    ListSortMode.CHILDREN -> documents.sortedWith(
                        Comparator { first, second ->
                            when {
                                first.isDirectory && !second.isDirectory -> -1

                                !first.isDirectory && second.isDirectory -> 1

                                else -> String.CASE_INSENSITIVE_ORDER.compare(
                                    first.displayName,
                                    second.displayName
                                )
                            }
                        }
                    )

                    ListSortMode.RECENT -> documents.sortedWith(
                        Comparator { first, second ->
                            (second.lastModified ?: 0L).compareTo(
                                first.lastModified ?: 0L
                            )
                        }
                    )
                }

                ProviderChildrenResult(
                    documents = sorted,
                    loading = cursor.extras.getBoolean(
                        DocumentsContract.EXTRA_LOADING,
                        false
                    ),
                    info = cursor.extras.getString(
                        DocumentsContract.EXTRA_INFO
                    ),
                    error = cursor.extras.getString(
                        DocumentsContract.EXTRA_ERROR
                    )
                )
            } ?: ProviderChildrenResult(
                documents = emptyList(),
                loading = false,
                info = null,
                error = appContext.getString(R.string.provider_no_data)
            )
        }
    }

    private fun queryProviderRoots(
        provider: DocumentProviderInfo,
        acceptedMimeTypes: List<String>,
        localOnly: Boolean,
        requireCreate: Boolean,
        cancellationSignal: CancellationSignal
    ): List<DocumentRootInfo> {
        val uri = DocumentsContract.buildRootsUri(provider.authority)
        val result = mutableListOf<DocumentRootInfo>()

        resolver.query(
            uri,
            ROOT_PROJECTION,
            null,
            null,
            null,
            cancellationSignal
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val root = rootFromCursor(provider, cursor)

                if (root.isEmpty) continue
                if (localOnly && !root.isLocalOnly) continue
                if (requireCreate && !root.supportsCreate) continue

                if (
                    !rootSupportsMimeTypes(
                        root,
                        acceptedMimeTypes
                    )
                ) {
                    continue
                }

                result += root
            }
        }

        return result
    }

    private fun rootFromCursor(provider: DocumentProviderInfo, cursor: Cursor): DocumentRootInfo {
        val title = cursor.string(DocumentsContract.Root.COLUMN_TITLE)
            ?.takeIf { it.isNotBlank() } ?: provider.label

        val mimeTypes = cursor.string(DocumentsContract.Root.COLUMN_MIME_TYPES)
            ?.split('\n')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

        return DocumentRootInfo(
            packageName = provider.packageName,
            authority = provider.authority,
            rootId = cursor.requiredString(DocumentsContract.Root.COLUMN_ROOT_ID),
            documentId = cursor.requiredString(DocumentsContract.Root.COLUMN_DOCUMENT_ID),
            title = title,
            summary = cursor.string(DocumentsContract.Root.COLUMN_SUMMARY),
            flags = cursor.int(DocumentsContract.Root.COLUMN_FLAGS),
            iconResId = cursor.int(DocumentsContract.Root.COLUMN_ICON),
            mimeTypes = mimeTypes
        )
    }

    private fun documentFromCursor(cursor: Cursor, authority: String, parentDocumentId: String?): ProviderDocumentInfo =
        ProviderDocumentInfo(
            authority = authority,
            documentId = cursor.requiredString(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            parentDocumentId = parentDocumentId,
            displayName = cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                ?: appContext.getString(R.string.unnamed),
            mimeType = cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE)
                ?: MimeTypes.BINARY,
            flags = cursor.int(DocumentsContract.Document.COLUMN_FLAGS),
            iconResId = cursor.int(DocumentsContract.Document.COLUMN_ICON),
            size = cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE),
            lastModified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        )

    private fun shouldShowDocument(
        document: ProviderDocumentInfo,
        mimeMatcher: (String) -> Boolean,
        directoriesOnly: Boolean,
        openableOnly: Boolean
    ): Boolean {
        if (document.isDirectory) return true
        if (directoriesOnly) return false
        if (openableOnly && document.isVirtual) return false

        return mimeMatcher(document.mimeType)
    }

    private fun rootSupportsMimeTypes(root: DocumentRootInfo, acceptedMimeTypes: List<String>): Boolean {
        val rootTypes = root.mimeTypes ?: return true

        return acceptedMimeTypes.any { requested ->
            rootTypes.any { supported ->
                MimeTypes.overlap(
                    requested,
                    supported
                )
            }
        }
    }

    private enum class ListSortMode {
        CHILDREN,
        PROVIDER_ORDER,
        RECENT
    }

    private data class RootQueryOutcome(
        val roots: List<DocumentRootInfo> = emptyList(),
        val error: DocumentProviderError? = null
    )

    companion object {
        private const val MAX_PARALLEL_ROOT_QUERIES = 4
        private val PROVIDER_QUERY_TIMEOUT = 5000.milliseconds
        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_ICON,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
