package com.luckycatpaw.luckyfilestv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luckycatpaw.luckyfilestv.ui.main.MainScreen
import com.luckycatpaw.luckyfilestv.ui.main.MainViewModel
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiEvent
import com.luckycatpaw.luckyfilestv.util.requestAllFilesAccess
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private var notificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect(::handleEvent)
            }
        }

        setContent {
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

            MainUiEvent.RequestNotificationAccess ->
                requestNotificationPermission()

            is MainUiEvent.ShowMessage ->
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequested) return

        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        if (granted) return

        notificationPermissionRequested = true

        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
