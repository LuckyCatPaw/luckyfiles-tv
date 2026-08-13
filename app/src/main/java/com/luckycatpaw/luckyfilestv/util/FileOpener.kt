package com.luckycatpaw.luckyfilestv.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.luckycatpaw.luckyfilestv.data.provider.FileContentProvider
import java.io.File

object FileOpener {

    fun open(
        context: Context,
        path: String
    ): Boolean {

        val file = File(path)

        if (!file.exists() || !file.isFile) {
            return false
        }

        val uri = FileContentProvider.createUri(
            context,
            file
        )

        val mimeType =
            context.contentResolver.getType(uri)
                ?: MimeTypes.ANY

        val intent = Intent(Intent.ACTION_VIEW).apply {

            setDataAndType(
                uri,
                mimeType
            )

            clipData = ClipData.newRawUri(
                file.name,
                uri
            )

            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        return try {

            context.startActivity(intent)

            true

        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
