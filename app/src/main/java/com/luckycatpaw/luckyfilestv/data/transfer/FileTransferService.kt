package com.luckycatpaw.luckyfilestv.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
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

    /**
     * Set as soon as the service has decided to shut down, so the state collector below
     * does not try to stop an already stopping service a second time.
     */
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferSession.cancel()
        }

        // Since Android 15 the system refuses to promote the service when the app has used
        // up its six hour dataSync budget. Stopping ourselves here is deliberate: the copy
        // cannot be kept alive reliably, and not calling startForeground() at all would
        // end in a ForegroundServiceDidNotStartInTimeException a few seconds later.
        if (!startForegroundCompat(buildNotification())) {
            abort(R.string.transfer_stopped_no_foreground)
            return START_NOT_STICKY
        }

        if (!observing) {
            observing = true
            serviceScope.launch {
                TransferSession.state.collectLatest { state ->
                    if (!state.running) {
                        if (!stopping) {
                            stopping = true
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        return@collectLatest
                    }

                    notificationManager().notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Called by the system once the app has spent six hours of dataSync foreground service
     * time within 24 hours (Android 15+). We have a few seconds to stop, otherwise the
     * system throws ForegroundServiceDidNotStopInTimeException and the process dies.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync time limit reached, cancelling running transfer")
        abort(R.string.transfer_stopped_time_limit)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Cancels the running transfer, tells the user why and shuts the service down.
     * Safe to call more than once.
     */
    private fun abort(messageRes: Int) {
        if (stopping) return
        stopping = true

        TransferSession.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyStopped(getString(messageRes))
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification): Boolean = try {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        true
    } catch (e: IllegalStateException) {
        // From Android 12 on this is a ForegroundServiceStartNotAllowedException, e.g.
        // "Time limit already exhausted for foreground service type dataSync". We catch
        // the superclass on purpose: the subclass does not exist on API 30 devices.
        Log.w(TAG, "Not allowed to start dataSync foreground service", e)
        false
    } catch (e: SecurityException) {
        // Foreground service permission missing or revoked.
        Log.w(TAG, "Missing permission for dataSync foreground service", e)
        false
    }

    /**
     * One-off notification explaining an interrupted transfer. Silently does nothing when
     * POST_NOTIFICATIONS was never granted, which is acceptable for a terminal message.
     */
    private fun notifyStopped(message: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .build()

        notificationManager().notify(STOPPED_NOTIFICATION_ID, notification)
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val state = TransferSession.state.value
        val progress = state.progress

        val title = getString(
            if (state.operation == TransferOperation.MOVE) R.string.moving else R.string.copying
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
            .setContentIntent(mainActivityIntent())
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
        private const val TAG = "FileTransferService"
        private const val CHANNEL_ID = "file_transfers"
        private const val NOTIFICATION_ID = 4711
        private const val STOPPED_NOTIFICATION_ID = 4712
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
