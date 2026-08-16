package com.luckycatpaw.luckyfilestv.data.provider

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.util.FileUtil
import java.io.File
import java.io.FileNotFoundException

internal class DocumentSecurityGuard(
    private val appContext: Context,
    private val idResolver: DocumentIdResolver
) {
    fun requireManagedFile(
        documentId: String,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): File {
        val file = idResolver.fromDocumentId(documentId)

        if (!isInsideManagedStorage(file, storageSnapshot)) {
            throw FileNotFoundException(appContext.getString(R.string.provider_outside_managed))
        }

        if (isSafRestrictedPath(file.path, storageSnapshot)) {
            throw FileNotFoundException(appContext.getString(R.string.provider_outside_managed))
        }

        if (!file.exists()) {
            throw FileNotFoundException(
                appContext.getString(R.string.provider_document_missing, file.absolutePath)
            )
        }

        return file
    }

    fun requireNotRoot(file: File, storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot) {
        if (isRootFile(file, storageSnapshot)) {
            throw FileNotFoundException(appContext.getString(R.string.provider_storage_roots_modify))
        }
    }

    fun requireDirectory(file: File) {
        if (!file.exists() || !file.isDirectory) {
            throw FileNotFoundException(
                appContext.getString(R.string.provider_not_directory, file.absolutePath)
            )
        }
    }

    fun requireWritable(file: File) {
        if (!file.canWrite()) {
            throw FileNotFoundException(
                appContext.getString(R.string.provider_read_only, file.absolutePath)
            )
        }
    }

    fun isInsideManagedStorage(
        file: File,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return isInsideManagedStoragePath(canonical.path, storageSnapshot)
    }

    fun isInsideManagedStoragePath(
        canonicalPath: String,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean {
        return storageSnapshot.roots.any { root ->
            FileUtil.isSameOrChildPath(root.path, canonicalPath)
        }
    }

    fun isRootFile(
        file: File,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return storageSnapshot.roots.any { it.path == canonical.path }
    }

    fun isSameOrChild(parent: File, child: File): Boolean = FileUtil.isSameOrChild(parent, child)

    fun isSafRestrictedPath(
        canonicalPath: String,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean {
        return storageSnapshot.restrictedRoots.any { root ->
            FileUtil.isSameOrChildPath(root.path, canonicalPath)
        }
    }

    fun blocksOpenDocumentTree(
        documentId: String,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean {
        val file = idResolver.fromDocumentId(documentId)
        val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return true
        return storageSnapshot.blockedTreePaths.contains(canonicalPath)
    }
}
