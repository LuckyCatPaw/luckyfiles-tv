package com.luckycatpaw.luckyfilestv.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.luckycatpaw.luckyfilestv.data.provider.FileContentProvider
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import java.io.File
import java.io.IOException

object FileOpener {

    fun open(context: Context, path: String): Boolean {
        val location = SourcePath.parseOrNull(path)
            ?: return false

        // Existence is only worth checking where it is free. On a share it would cost a
        // round trip to tell the player something it finds out itself a moment later.
        if (location.isLocal && !File(path).isFile) {
            return false
        }

        return try {
            val uri = FileContentProvider.createUri(
                context,
                canonicalPathOf(location)
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
                    location.name,
                    uri
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            context.startActivity(intent)

            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    private fun canonicalPathOf(location: SourcePath): String =
        if (location.isLocal) location.toFile().canonicalPath else location.value
}
