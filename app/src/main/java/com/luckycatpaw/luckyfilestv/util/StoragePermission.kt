package com.luckycatpaw.luckyfilestv.util

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri
import android.os.Environment
import android.provider.Settings

fun hasAllFilesAccess(): Boolean {
    return Environment.isExternalStorageManager()
}

fun requestAllFilesAccess(activity: Activity) {

    val appIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${activity.packageName}".toUri()
    )

    try {
        activity.startActivity(appIntent)
    } catch (_: Exception) {

        val fallbackIntent = Intent(
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        )

        activity.startActivity(fallbackIntent)
    }
}
