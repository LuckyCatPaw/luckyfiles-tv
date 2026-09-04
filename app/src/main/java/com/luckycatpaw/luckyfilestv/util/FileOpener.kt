package com.luckycatpaw.luckyfilestv.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.luckycatpaw.luckyfilestv.data.provider.FileContentProvider
import com.luckycatpaw.luckyfilestv.data.provider.RemoteAccessService
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

        if (!location.isLocal) {
            // Has to happen here, not when the descriptor is opened: by then the player is
            // in front and a background app may no longer start a foreground service.
            RemoteAccessService.start(context)
        }

        val started = try {
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

                // The package installer runs in its own task and refuses to be started as
                // part of ours.
                if (mimeType == MimeTypes.APK) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
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

        if (!started && !location.isLocal) {
            // Nothing will open a descriptor now, so the service would sit there until its
            // own timeout expires.
            RemoteAccessService.stopIfIdle(context)
        }

        return started
    }

    private fun canonicalPathOf(location: SourcePath): String =
        if (location.isLocal) location.toFile().canonicalPath else location.value
}
