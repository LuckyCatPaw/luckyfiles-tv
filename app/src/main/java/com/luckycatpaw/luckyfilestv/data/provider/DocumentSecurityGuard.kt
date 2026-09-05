package com.luckycatpaw.luckyfilestv.data.provider

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.util.FileUtil
import java.io.File
import java.io.FileNotFoundException

internal class DocumentSecurityGuard(private val appContext: Context, private val idResolver: DocumentIdResolver) {

    /**
     * Resolves a document id to the file it names, or refuses it.
     *
     * The path is canonicalised once and every check from here on runs on that one value,
     * including the ones performed by the caller afterwards. Mixing the two forms is what
     * makes a guard like this leak: the containment check used to canonicalise while the
     * restricted-root check did not, so an id carrying a `..` segment — or pointing at a
     * symbolic link — was measured against a spelling that did not begin with
     * `Android/data` while the kernel happily resolved it there on open. One form, one
     * decision, and the file handed back is the same one that was approved.
     */
    fun requireManagedFile(documentId: String, storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot): File {
        val requested = idResolver.fromDocumentId(documentId)

        // An unresolvable path is not a file this provider can vouch for, so it is refused
        // rather than checked in its literal form.
        val file = runCatching { requested.canonicalFile }.getOrNull()
            ?: throw FileNotFoundException(appContext.getString(R.string.provider_outside_managed))

        if (!isInsideManagedStoragePath(file.path, storageSnapshot)) {
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

    fun isInsideManagedStorage(file: File, storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return isInsideManagedStoragePath(canonical.path, storageSnapshot)
    }

    fun isInsideManagedStoragePath(
        canonicalPath: String,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean = storageSnapshot.roots.any { root ->
        FileUtil.isSameOrChildPath(root.path, canonicalPath)
    }

    fun isRootFile(file: File, storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return isRootPath(canonical.path, storageSnapshot)
    }

    fun isRootPath(canonicalPath: String, storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot): Boolean =
        canonicalPath in storageSnapshot.rootPaths

    fun isSafRestrictedPath(
        canonicalPath: String,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean = storageSnapshot.restrictedRoots.any { root ->
        FileUtil.isSameOrChildPath(root.path, canonicalPath)
    }

    fun blocksOpenDocumentTree(
        canonicalPath: String,
        storageSnapshot: DocumentIdResolver.ManagedStorageSnapshot
    ): Boolean = storageSnapshot.blockedTreePaths.contains(canonicalPath)
}
