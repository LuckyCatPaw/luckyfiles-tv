package com.luckycatpaw.luckyfilestv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import com.luckycatpaw.luckyfilestv.ui.main.MainScreen
import com.luckycatpaw.luckyfilestv.ui.main.MainViewModel
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiEvent
import com.luckycatpaw.luckyfilestv.util.requestAllFilesAccess

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            LaunchedEffect(viewModel) {
                viewModel.events.collect(::handleEvent)
            }

            MainScreen(viewModel)
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

    private fun handleEvent(event: MainUiEvent) {
        when (event) {
            MainUiEvent.RequestStorageAccess ->
                requestAllFilesAccess(this)

            is MainUiEvent.ShowMessage ->
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
        }
    }
}
