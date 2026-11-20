package com.pureshield.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pureshield.app.R

class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LOCK, context.getString(R.string.channel_lock), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DAILY, context.getString(R.string.channel_daily), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent().setClassName(context.packageName, "com.pureshield.app.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** High-priority alert, e.g. a zone was entered or protection was interrupted. */
    fun alert(id: Int, title: String, text: String) = notify(id, CH_ALERTS, title, text)

    /** Low-key information, e.g. "lock completed". */
    fun event(id: Int, title: String, text: String) = notify(id, CH_EVENTS, title, text)

    /** Daily summary notification. */
    fun daily(id: Int, title: String, text: String) = notify(id, CH_DAILY, title, text)

    private fun notify(id: Int, channel: String, title: String, text: String) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) manager.notify(id, n)
        } catch (_: SecurityException) {
            // Notification permission was revoked between the check and the call: nothing to do.
        }
    }

    /** Ongoing foreground notification for the keep-alive service. */
    fun ongoingLockNotification(text: String): Notification =
        NotificationCompat.Builder(context, CH_LOCK)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(context.getString(R.string.notif_lock_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    companion object {
        const val CH_LOCK = "lock"
        const val CH_ALERTS = "alerts"
        const val CH_EVENTS = "events"
        const val CH_DAILY = "daily"

        const val ID_UNLOCKED = 1003
        const val ID_ZONE = 1005
        const val ID_BOOT = 1006
        const val ID_LOCATION_DENIED = 1007
        const val ID_DAILY = 1008
        const val ID_BOUNDARY = 1009
    }
}
