package com.luckycatpaw.luckyfilestv.ui.picker.model

import android.net.Uri

internal sealed interface PickerUiEvent {
    data class Finish(val uris: List<Uri>) : PickerUiEvent
    object Cancel : PickerUiEvent
    object RequestStorageAccess : PickerUiEvent
}
