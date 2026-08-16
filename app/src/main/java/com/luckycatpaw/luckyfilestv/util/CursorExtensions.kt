package com.luckycatpaw.luckyfilestv.util

import android.database.Cursor

internal fun Cursor.string(column: String): String? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return getString(index)
}

internal fun Cursor.requiredString(column: String): String {
    return string(column) ?: error("Required column $column missing")
}

internal fun Cursor.int(column: String): Int {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return 0
    return getInt(index)
}

internal fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return getLong(index)
}

internal fun Cursor.boolean(column: String): Boolean {
    return int(column) != 0
}
