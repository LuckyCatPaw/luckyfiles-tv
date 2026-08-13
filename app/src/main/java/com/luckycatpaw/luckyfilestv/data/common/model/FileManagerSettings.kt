package com.luckycatpaw.luckyfilestv.data.common.model

enum class FileSortMode {
    NAME,
    DATE,
    SIZE,
    TYPE
}

data class FileManagerSettings(
    val languageTag: String? = null,
    val hideFolderJpg: Boolean = true,
    val useFolderJpgAsIcon: Boolean = true,
    val optimizeFileNames: Boolean = true,
    val sortMode: FileSortMode = FileSortMode.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true
)
