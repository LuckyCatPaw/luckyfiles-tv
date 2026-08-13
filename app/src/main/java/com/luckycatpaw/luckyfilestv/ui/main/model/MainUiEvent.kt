package com.luckycatpaw.luckyfilestv.ui.main.model

internal sealed interface MainUiEvent {
    object RequestStorageAccess : MainUiEvent
    data class ShowMessage(val message: String) : MainUiEvent
}
