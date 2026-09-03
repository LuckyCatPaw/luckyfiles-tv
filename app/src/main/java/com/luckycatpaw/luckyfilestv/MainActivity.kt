package com.luckycatpaw.luckyfilestv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luckycatpaw.luckyfilestv.ui.main.MainScreen
import com.luckycatpaw.luckyfilestv.ui.main.MainViewModel
import com.luckycatpaw.luckyfilestv.ui.main.model.MainUiEvent
import com.luckycatpaw.luckyfilestv.util.ACCESS_LOCAL_NETWORK
import com.luckycatpaw.luckyfilestv.util.requestAllFilesAccess
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.resumeAfterLocalNetworkPermission()
    }

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

            MainUiEvent.RequestLocalNetworkAccess ->
                requestLocalNetworkPermission()

            is MainUiEvent.ShowMessage ->
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Android 17 gates the local network behind its own permission. Denying it leaves the
     * share unreachable with an ordinary socket error, so the request happens before the
     * first connection rather than after a failure nobody can interpret.
     */
    private fun requestLocalNetworkPermission() {
        runCatching { localNetworkPermissionLauncher.launch(ACCESS_LOCAL_NETWORK) }
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
