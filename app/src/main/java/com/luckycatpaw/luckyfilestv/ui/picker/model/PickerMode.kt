package com.luckycatpaw.luckyfilestv.ui.picker.model

import android.content.Intent

enum class PickerMode {
    OPEN_DOCUMENT,
    CREATE_DOCUMENT,
    OPEN_DOCUMENT_TREE,
    GET_CONTENT;

    companion object {
        fun fromIntent(intent: Intent): PickerMode? {
            return when (intent.action) {
                Intent.ACTION_OPEN_DOCUMENT -> OPEN_DOCUMENT
                Intent.ACTION_CREATE_DOCUMENT -> CREATE_DOCUMENT
                Intent.ACTION_OPEN_DOCUMENT_TREE -> OPEN_DOCUMENT_TREE
                Intent.ACTION_GET_CONTENT -> GET_CONTENT
                else -> null
            }
        }
    }
}
