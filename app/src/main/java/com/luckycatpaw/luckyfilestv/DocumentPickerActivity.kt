package com.luckycatpaw.luckyfilestv

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import com.luckycatpaw.luckyfilestv.ui.picker.DocumentPickerScreen
import com.luckycatpaw.luckyfilestv.ui.picker.DocumentPickerViewModel
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerRequest
import com.luckycatpaw.luckyfilestv.ui.picker.model.PickerUiEvent
import com.luckycatpaw.luckyfilestv.util.requestAllFilesAccess

class DocumentPickerActivity : ComponentActivity() {

    private lateinit var viewModel: DocumentPickerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = PickerRequest.fromIntent(this, intent)

        if (request == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        viewModel = ViewModelProvider(this).get(DocumentPickerViewModel::class.java)
        viewModel.initialize(request)

        setContent {
            LaunchedEffect(viewModel) {
                viewModel.events.collect(::handleEvent)
            }

            DocumentPickerScreen(viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startWatchingStorage()
    }

    override fun onStop() {
        viewModel.stopWatchingStorage()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeAfterStoragePermission()
    }

    private fun handleEvent(event: PickerUiEvent) {
        when (event) {
            PickerUiEvent.Cancel -> {
                setResult(RESULT_CANCELED)
                finish()
            }

            PickerUiEvent.RequestStorageAccess ->
                requestAllFilesAccess(this)

            is PickerUiEvent.Finish ->
                finishWithUris(event.uris)
        }
    }

    private fun finishWithUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val result = Intent()

        if (uris.size == 1) {
            result.data = uris.first()
        } else {
            val clip = ClipData(
                null,
                viewModel.request.acceptedMimeTypes.toTypedArray(),
                ClipData.Item(uris.first())
            )

            uris.drop(1).forEach {
                clip.addItem(ClipData.Item(it))
            }

            result.clipData = clip
        }

        result.addFlags(viewModel.resultGrantFlags())
        setResult(RESULT_OK, result)
        finish()
    }
}
