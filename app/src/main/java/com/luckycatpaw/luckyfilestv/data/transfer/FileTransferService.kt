package com.luckycatpaw.luckyfilestv.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.luckycatpaw.luckyfilestv.MainActivity
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class FileTransferService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferSession.cancel()
        }

        startForegroundCompat(buildNotification())

        if (!observing) {
            observing = true
            serviceScope.launch {
                TransferSession.state.collectLatest { state ->
                    if (!state.running) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }

                    notificationManager().notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun buildNotification(): Notification {
        val state = TransferSession.state.value
        val progress = state.progress

        val title = getString(
            if (state.operation == TransferOperation.MOVE) R.string.moving else R.string.copying
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FileTransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(progress?.currentName.orEmpty())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.cancel),
                    cancelIntent
                ).build()
            )

        if (progress != null && progress.totalBytes > 0L) {
            val percent = (progress.bytesProcessed * 100L / progress.totalBytes)
                .coerceIn(0L, 100L)
                .toInt()
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "file_transfers"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_CANCEL = "com.luckycatpaw.luckyfilestv.action.CANCEL_TRANSFER"

        fun start(context: Context) {
            val appContext = context.applicationContext
            ensureChannel(appContext)
            appContext.startForegroundService(
                Intent(appContext, FileTransferService::class.java)
            )
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)

            if (manager.getNotificationChannel(CHANNEL_ID) != null) return

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.transfer_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                }
            )
        }
    }
}
