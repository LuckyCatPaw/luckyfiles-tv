package com.luckycatpaw.luckyfilestv.data.common.model

data class FileProperties(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val fileCount: Long,
    val folderCount: Long,
    val extension: String?,
    val mimeType: String?
)
