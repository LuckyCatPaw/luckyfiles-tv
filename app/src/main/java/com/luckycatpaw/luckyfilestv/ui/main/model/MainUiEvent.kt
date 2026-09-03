package com.luckycatpaw.luckyfilestv.ui.main.model

internal sealed interface MainUiEvent {
    object RequestStorageAccess : MainUiEvent
    object RequestNotificationAccess : MainUiEvent
    object RequestLocalNetworkAccess : MainUiEvent
    data class ShowMessage(val message: String) : MainUiEvent
}
