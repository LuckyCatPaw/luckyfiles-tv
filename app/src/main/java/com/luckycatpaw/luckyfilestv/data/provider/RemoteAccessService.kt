package com.luckycatpaw.luckyfilestv.data.provider

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.ensureNotificationChannel
import com.luckycatpaw.luckyfilestv.data.common.startForegroundOrFalse
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process in the foreground while another app reads a file from a share.
 *
 * Android takes the network away from a background process. Handing a video to a player
 * looks exactly like that from the outside: our app disappears, the player comes up, and a
 * few seconds later the connection underneath the file descriptor is cut — playback stalls
 * on a read that can no longer be answered, and reconnecting fails for the same reason.
 *
 * The service is started while the app is still visible, immediately before the player is
 * launched. Starting it later would be refused: from Android 12 on a background app may not
 * start a foreground service.
 */
internal class RemoteAccessService : Service() {

    private val watchdog = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.remote_access_notification))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        if (!startForegroundOrFalse(NOTIFICATION_ID, notification)) {
            stopSelf()
            return START_NOT_STICKY
        }

        awaitFirstDescriptor()
        return START_STICKY
    }

    override fun onDestroy() {
        watchdog.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Stops again when no one ever reads.
     *
     * The service is started before the other app is launched, so at that point nothing can
     * be counted yet. If the launch fails, or the player never opens the file, nothing would
     * ever bring the service down — and a foreground notification that outlives its reason
     * is worse than a lost second of network.
     */
    private fun awaitFirstDescriptor() {
        watchdog.removeCallbacksAndMessages(null)
        watchdog.postDelayed({ stopIfIdle(this) }, GRACE_PERIOD_MILLIS)
    }

    companion object {

        private const val CHANNEL_ID = "remote_access"
        private const val NOTIFICATION_ID = 4713

        /** Long enough for a player to start up on a slow TV, short enough not to linger. */
        private const val GRACE_PERIOD_MILLIS = 30_000L

        private val openDescriptors = AtomicInteger(0)

        /**
         * Called before the player is launched, while the app still may start a service.
         *
         * The descriptor is opened a moment later by the other app, so the count cannot be
         * what starts this — it would always be zero at this point.
         */
        fun start(context: Context) {
            val appContext = context.applicationContext
            ensureNotificationChannel(appContext, CHANNEL_ID, R.string.remote_access_channel_name)

            runCatching {
                appContext.startForegroundService(Intent(appContext, RemoteAccessService::class.java))
            }
        }

        fun descriptorOpened() {
            openDescriptors.incrementAndGet()
        }

        /**
         * Stops the service once the last reader is gone.
         *
         * Floored rather than decremented blindly. The count is process wide and the
         * service start is best effort, so a release without a matching open is not a
         * hypothetical: a descriptor handed out while the service was refused, or one that
         * outlives a restart of the process, arrives here on its own. Left negative the
         * count never climbs back above zero, at which point [stopIfIdle] reads a busy
         * service as idle and takes the foreground state away from a reader that is still
         * going — which is the exact stall this service exists to prevent.
         */
        fun descriptorClosed(context: Context) {
            val remaining = openDescriptors.updateAndGet { current -> (current - 1).coerceAtLeast(0) }

            if (remaining > 0) return

            stop(context)
        }

        /** Used when launching the other app failed, so nothing will ever read. */
        fun stopIfIdle(context: Context) {
            if (openDescriptors.get() > 0) return

            stop(context)
        }

        private fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching { appContext.stopService(Intent(appContext, RemoteAccessService::class.java)) }
        }
    }
}
