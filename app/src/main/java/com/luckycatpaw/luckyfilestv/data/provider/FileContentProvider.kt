package com.luckycatpaw.luckyfilestv.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.util.Base64
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

class FileContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.read_only_access))
        }

        val file = resolveFile(uri)

        if (!file.exists() || !file.isFile) {
            throw FileNotFoundException(file.absolutePath)
        }

        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun getType(uri: Uri): String {
        val file = resolveFile(uri)

        return MimeTypes.forFileName(file.name)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFile(uri)

        val requestedColumns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        )

        val cursor = MatrixCursor(requestedColumns)

        val values = requestedColumns.map { column ->

            when (column) {
                OpenableColumns.DISPLAY_NAME ->
                    file.name

                OpenableColumns.SIZE ->
                    file.length()

                else ->
                    null
            }
        }.toTypedArray<Any?>()

        cursor.addRow(values)

        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun resolveFile(uri: Uri): File {
        val encoded = uri.lastPathSegment
            ?: throw FileNotFoundException()

        val decodedPath = try {
            String(
                Base64.decode(
                    encoded,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                StandardCharsets.UTF_8
            )
        } catch (error: IllegalArgumentException) {
            throw FileNotFoundException(error.message)
        }

        val file = File(decodedPath).canonicalFile

        if (!isAllowedFile(file)) {
            throw SecurityException(
                requireNotNull(context).getString(R.string.path_outside_storage)
            )
        }

        return file
    }

    private fun isAllowedFile(file: File): Boolean {
        val context = context ?: return false

        val storageManager =
            context.getSystemService(StorageManager::class.java)

        val filePath = file.canonicalPath

        return storageManager.storageVolumes.any { volume ->

            val root = volume.directory
                ?.canonicalFile
                ?: return@any false

            val rootPath = root.canonicalPath

            filePath == rootPath ||
                filePath.startsWith(
                    rootPath + File.separator
                )
        }
    }

    companion object {

        fun createUri(context: android.content.Context, file: File): Uri {
            val encoded = Base64.encodeToString(
                file.canonicalPath.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
            )

            return Uri.Builder()
                .scheme("content")
                .authority(
                    "${context.packageName}.files"
                )
                .appendPath(encoded)
                .build()
        }
    }
}
