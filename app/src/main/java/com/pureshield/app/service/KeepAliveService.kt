package com.pureshield.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.location.PlaceWatcher
import com.pureshield.app.util.DurationFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sticky foreground service that keeps protection alive while the app is swiped away.
 *
 * - Shows an ongoing notification with the live countdown (refreshed every 30 s) or, when
 *   only the location lock is on, "location lock active".
 * - Runs the PlaceWatcher 90-second foreground loop while the location lock is on.
 * - Restarts itself via AlarmManager.setAlarmClock (no permission, always exact, and it
 *   carries a foreground-service-start exemption) when it is killed or swiped away.
 *
 * Limit (same as PowerLock): if the system force-stops the app, alarms and receivers stay
 * dead until the app is opened again — Recents lock + Autostart on + battery optimisation
 * off remain required on Vivo/MIUI devices.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private var lastRemainingMs = -1L

    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, Graph.notifier.ongoingLockNotification(getString(R.string.notif_lock_running_text)))
        handler.postDelayed(tickRunnable, TICK_MS)
        scope.launch {
            if (Graph.settings.get().locationLockEnabled) {
                PlaceWatcher.startForegroundLoop(this@KeepAliveService)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, Graph.notifier.ongoingLockNotification(getString(R.string.notif_lock_running_text)))
        refreshNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from Recents: schedule a restart and try to come back at once.
        restartSoon(this)
        runCatching {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        PlaceWatcher.stopForegroundLoop()
        isRunning = false
        // If protection is still on, the service being destroyed must not end it.
        // Read the in-memory snapshot (no suspend call: the scope is being torn down).
        if (runCatching { Graph.lockManager.state.value.protecting }.getOrDefault(false)) {
            restartSoon(this)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshNotification() {
        scope.launch {
            val snapshot = runCatching { Graph.lockManager.snapshotNow() }.getOrNull() ?: return@launch
            val text = when {
                snapshot.active && snapshot.remainingMs > 0 ->
                    getString(R.string.notif_lock_countdown_text, DurationFormat.clock(snapshot.remainingMs))
                snapshot.locProtecting ->
                    getString(R.string.notif_location_lock_text)
                else -> getString(R.string.notif_lock_running_text)
            }
            // Only re-post when the displayed minute changed, to avoid notification spam.
            val bucket = snapshot.remainingMs / 60_000L
            if (bucket != lastRemainingMs / 60_000L || lastRemainingMs < 0) {
                lastRemainingMs = snapshot.remainingMs
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NOTIF_ID, Graph.notifier.ongoingLockNotification(text))
            }
        }
    }

    companion object {
        // NOTE: 2100 on purpose — the permission prompts use 2001-2006, and
        // sharing an id would let one notification cancel/replace the other.
        const val NOTIF_ID = 2100
        private const val TICK_MS = 30_000L
        // Restart gap after a Recents swipe: 5 s, not a minute — setAlarmClock
        // is exact and needs no permission, so there is no reason to leave
        // protection down for 60 s. (Vivo force-stop is still unrecoverable:
        // see the class comment.)
        private const val RESTART_DELAY_MS = 5_000L

        /** True while the service object is alive in this process. A fresh
         *  process (e.g. after a swipe-kill) starts with false, which is
         *  exactly what the GuardWorker watchdog checks. */
        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }

        /** Schedules a restart of this service via setAlarmClock (exact, no permission needed). */
        fun restartSoon(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, KeepAliveService::class.java)
                val pi = PendingIntent.getService(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, null),
                    pi
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
