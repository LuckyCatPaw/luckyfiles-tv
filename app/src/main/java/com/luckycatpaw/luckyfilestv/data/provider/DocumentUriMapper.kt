package com.luckycatpaw.luckyfilestv.data.provider

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import java.io.File
import java.nio.charset.StandardCharsets

object DocumentUriMapper {

    fun documentUri(context: Context, path: String): Uri = DocumentsContract.buildDocumentUri(
        authority(context),
        documentId(path)
    )

    fun treeUri(context: Context, path: String): Uri = DocumentsContract.buildTreeDocumentUri(
        authority(context),
        documentId(path)
    )

    fun documentId(path: String): String = documentIdFromCanonicalPath(File(path).canonicalFile.absolutePath)

    fun documentIdFromCanonicalPath(canonicalPath: String): String {
        val encoded = Base64.encodeToString(
            canonicalPath.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        return "path:$encoded"
    }

    fun pathFromDocumentId(documentId: String): String? {
        if (!documentId.startsWith("path:")) {
            return null
        }

        return runCatching {
            val encoded = documentId.removePrefix("path:")

            val bytes = Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            File(
                String(bytes, StandardCharsets.UTF_8)
            ).canonicalFile.absolutePath
        }.getOrNull()
    }

    private fun authority(context: Context): String = "${context.packageName}.documents"
}
