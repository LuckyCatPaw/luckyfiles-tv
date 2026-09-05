package com.luckycatpaw.luckyfilestv.data.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.StringRes

private const val LOG_TAG = "ForegroundService"

/** The notification manager, which both services reach for on every notification they post. */
internal fun Context.notificationManager(): NotificationManager =
    getSystemService(NotificationManager::class.java)

/**
 * Creates the channel if it is not there yet.
 *
 * Called before the service is started rather than from within it: a foreground service has
 * to post its notification within a few seconds of starting, and a missing channel makes
 * that post a no-op, which the system then treats as a service that never went foreground.
 */
internal fun ensureNotificationChannel(context: Context, channelId: String, @StringRes nameRes: Int) {
    val manager = context.notificationManager()

    if (manager.getNotificationChannel(channelId) != null) return

    manager.createNotificationChannel(
        NotificationChannel(
            channelId,
            context.getString(nameRes),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
    )
}

/**
 * Goes foreground, reporting whether the system allowed it.
 *
 * Both refusals are ordinary outcomes rather than bugs, which is why they are a `false` and
 * not an exception. From Android 12 on the first is really a
 * `ForegroundServiceStartNotAllowedException` — for example "Time limit already exhausted for
 * foreground service type dataSync" — and the superclass is caught on purpose, because that
 * subclass does not exist on API 30 and referencing it would fail to resolve there.
 *
 * A caller that gets `false` has to stop itself. Not calling [Service.startForeground] at all
 * ends in a `ForegroundServiceDidNotStartInTimeException` seconds later, which is a crash
 * rather than a message.
 */
internal fun Service.startForegroundOrFalse(
    notificationId: Int,
    notification: Notification,
    foregroundServiceType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
): Boolean = try {
    startForeground(notificationId, notification, foregroundServiceType)
    true
} catch (refused: IllegalStateException) {
    Log.w(LOG_TAG, "Not allowed to start ${javaClass.simpleName} in the foreground", refused)
    false
} catch (refused: SecurityException) {
    Log.w(LOG_TAG, "Missing permission for ${javaClass.simpleName} in the foreground", refused)
    false
}
