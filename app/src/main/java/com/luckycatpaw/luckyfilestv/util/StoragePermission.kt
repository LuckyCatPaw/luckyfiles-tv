package com.luckycatpaw.luckyfilestv.util

import android.app.Activity
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import com.luckycatpaw.luckyfilestv.R

fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

fun requestAllFilesAccess(activity: Activity): Boolean {
    val packageUri = "package:${activity.packageName}".toUri()

    val candidates = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    )

    for (intent in candidates) {
        val started = runCatching {
            activity.startActivity(intent)
        }.isSuccess

        if (started) return true
    }

    Toast.makeText(
        activity,
        R.string.storage_permission_screen_missing,
        Toast.LENGTH_LONG
    ).show()

    return false
}
