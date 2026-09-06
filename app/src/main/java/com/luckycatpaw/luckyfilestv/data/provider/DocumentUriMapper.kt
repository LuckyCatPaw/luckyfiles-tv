package com.luckycatpaw.luckyfilestv.data.provider

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

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
        val encoded = ENCODER.encodeToString(canonicalPath.toByteArray(StandardCharsets.UTF_8))

        return "$PREFIX$encoded"
    }

    fun pathFromDocumentId(documentId: String): String? {
        if (!documentId.startsWith(PREFIX)) {
            return null
        }

        return runCatching {
            val bytes = DECODER.decode(documentId.removePrefix(PREFIX))

            File(
                String(bytes, StandardCharsets.UTF_8)
            ).canonicalFile.absolutePath
        }.getOrNull()
    }

    private fun authority(context: Context): String = "${context.packageName}.documents"

    private const val PREFIX = "path:"

    /**
     * The same alphabet android.util.Base64 produced with URL_SAFE, NO_WRAP and NO_PADDING,
     * so every document ID already handed out stays valid. Taken from java.util because
     * that one exists on the JVM as well: the encoding is the whole of what a document ID
     * is, and it could not be tested while it lived in the Android stub.
     *
     * The decoder is stricter than Android's, which also accepted the standard alphabet.
     * Nothing produces those IDs, and an ID that is not ours should be refused anyway.
     */
    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
}
