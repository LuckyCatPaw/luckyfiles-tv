package com.luckycatpaw.luckyfilestv.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)

    return when {
        value >= TB -> String.format(Locale.getDefault(), "%.2f TB", value / TB)
        value >= GB -> String.format(Locale.getDefault(), "%.2f GB", value / GB)
        value >= MB -> String.format(Locale.getDefault(), "%.1f MB", value / MB)
        value >= KB -> String.format(Locale.getDefault(), "%.1f KB", value / KB)
        else -> "$value B"
    }
}

private const val KB = 1024.0
private const val MB = KB * 1024.0
private const val GB = MB * 1024.0
private const val TB = GB * 1024.0
