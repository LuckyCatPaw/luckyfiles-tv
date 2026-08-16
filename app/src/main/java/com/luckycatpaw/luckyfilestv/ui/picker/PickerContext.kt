package com.luckycatpaw.luckyfilestv.ui.picker

import android.content.Context
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.repository.DocumentsProviderRepository
import com.luckycatpaw.luckyfilestv.data.repository.LocalFileSearchRepository
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerMode
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerRequest
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Everything the picker handlers share.
 *
 * Previously each handler took the same six dependencies individually, plus
 * `getRequest` / `getSettings` lambdas so it could read state owned by the ViewModel.
 * Holding [request] and [settings] here removes those accessors: the ViewModel writes
 * them, the handlers read them.
 */
internal class PickerContext(
    val appContext: Context,
    val modelScope: CoroutineScope,
    val uiState: MutableStateFlow<PickerUiState>,
    val documentsRepository: DocumentsProviderRepository,
    val localSearchRepository: LocalFileSearchRepository,
    val providerQueryRunner: ProviderQueryRunner,
    initialRequest: PickerRequest
) {
    var request: PickerRequest = initialRequest
    var settings: FileManagerSettings = FileManagerSettings()

    /** Set by the ViewModel; handlers call it after changing the current location. */
    var onUiMetadataChanged: () -> Unit = {}

    /** True for the two modes that can only select a directory. */
    val directoriesOnly: Boolean
        get() = request.mode == PickerMode.CREATE_DOCUMENT || request.mode == PickerMode.OPEN_DOCUMENT_TREE

    fun getString(resId: Int): String = appContext.getString(resId)

    fun getString(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    fun getQuantityString(resId: Int, quantity: Int, vararg args: Any): String =
        appContext.resources.getQuantityString(resId, quantity, *args)
}
