package com.luckycatpaw.luckyfilestv.data.provider

import java.io.File

internal class DocumentIdResolver {

    fun toDocumentId(file: File): String {
        return DocumentUriMapper.documentId(file.canonicalPath)
    }

    fun toDocumentIdFromCanonicalPath(canonicalPath: String): String {
        return DocumentUriMapper.documentId(canonicalPath)
    }

    fun fromDocumentId(documentId: String): File {
        val path = DocumentUriMapper.pathFromDocumentId(documentId)
            ?: throw IllegalArgumentException("Invalid document ID")
        return File(path)
    }

    data class ManagedStorageSnapshot(
        val roots: List<File>,
        val namesByRootPath: Map<String, String>
    ) {
        val restrictedRoots: List<File> = roots.flatMap { root ->
            listOf(
                File(root, "Android/data").canonicalOrAbsolute(),
                File(root, "Android/obb").canonicalOrAbsolute()
            )
        }

        val blockedTreePaths: Set<String> = roots.flatMap { root ->
            listOf(
                root.path,
                File(root, "Download").canonicalOrAbsolute().path
            )
        }.toSet()

        private fun File.canonicalOrAbsolute(): File {
            return runCatching { canonicalFile }.getOrElse { absoluteFile }
        }
    }
}
