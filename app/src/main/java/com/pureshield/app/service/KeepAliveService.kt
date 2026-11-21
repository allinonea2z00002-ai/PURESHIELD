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
import android.provider.Settings
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.location.PlaceWatcher
import com.pureshield.app.util.DurationFormat
import com.pureshield.app.util.PermissionPrompter
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
 * Why foregroundServiceType is dataSync, not location: on Android 14+ a service
 * promoted with the location type throws SecurityException unless the app already
 * holds ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION — and location is only
 * granted when the location lock is used, so most users have not granted it. The
 * crash happened inside onCreate (after startForegroundService had already
 * returned), so the keep-alive silently never ran and the process died the moment
 * the app left the foreground. dataSync needs no runtime permission, and the
 * keep-alive itself does not need location access (the PlaceWatcher loop checks
 * the permission itself on every iteration).
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
            // A throwing tick (watchdog notification, nm.notify,
            // getString) must never kill the process — and the tick
            // itself must keep running, so the re-post stays outside.
            runCatching { refreshNotification() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // startForeground can throw (e.g. background-start restrictions):
        // swallow it — a crash here would kill the whole process and
        // the service with it.
        runCatching {
            startForeground(NOTIF_ID, Graph.notifier.ongoingLockNotification(getString(R.string.notif_lock_running_text)))
        }
        handler.postDelayed(tickRunnable, TICK_MS)
        scope.launch {
            runCatching {
                if (Graph.settings.get().locationLockEnabled) {
                    PlaceWatcher.startForegroundLoop(this@KeepAliveService)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            startForeground(NOTIF_ID, Graph.notifier.ongoingLockNotification(getString(R.string.notif_lock_running_text)))
            refreshNotification()
        }
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
        // Diagnostics (Home "সার্ভিস স্ট্যাটাস" card): prove the keep-alive is
        // actually alive. Written on every tick and on every service start.
        getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_KEEPALIVE_TICK, System.currentTimeMillis()).apply()
        checkA11yWatchdog()
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

    /**
     * Accessibility watchdog, run on every 30 s tick. Derives the state
     * (Settings check + last-event age) and stores it in the "diag" prefs
     * under KEY_A11Y_STATE — the Home card's 5th row reads it.
     *
     * Notifications post ONLY on a transition into "off" or "stale", and
     * are cancelled on the transition back to "on" — never every tick.
     * A "stale" candidate must persist across a second tick before it
     * counts, so turning accessibility on does not flash a false alert
     * before the first event arrives.
     */
    private fun checkA11yWatchdog() {
        val candidate = deriveA11yState(this)
        val prefs = getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_A11Y_STATE, null)
        val now = System.currentTimeMillis()
        if (candidate == A11Y_STATE_STALE) {
            val since = prefs.getLong(KEY_A11Y_STALE_SINCE, 0L)
            if (since <= 0L) {
                prefs.edit().putLong(KEY_A11Y_STALE_SINCE, now).apply()
                return
            }
            if (now - since < A11Y_STALE_CONFIRM_MS) return
            prefs.edit().remove(KEY_A11Y_STALE_SINCE).apply()
        } else if (prefs.contains(KEY_A11Y_STALE_SINCE)) {
            prefs.edit().remove(KEY_A11Y_STALE_SINCE).apply()
        }
        if (candidate == prev) return
        prefs.edit().putString(KEY_A11Y_STATE, candidate).apply()
        onA11yStateChanged(candidate)
    }

    /** Posts the watchdog alert for the new state, cancelling the other one. */
    private fun onA11yStateChanged(state: String) {
        val nm = Graph.notifier
        when (state) {
            A11Y_STATE_OFF -> {
                nm.cancel(NOTIF_A11Y_STALE_ID)
                nm.permissionPrompt(
                    NOTIF_A11Y_OFF_ID,
                    getString(R.string.a11y_watchdog_off_title),
                    getString(R.string.a11y_watchdog_off_text),
                    accessibilitySettingsIntent()
                )
            }
            A11Y_STATE_STALE -> {
                nm.cancel(NOTIF_A11Y_OFF_ID)
                nm.permissionPrompt(
                    NOTIF_A11Y_STALE_ID,
                    getString(R.string.a11y_watchdog_stale_title),
                    getString(R.string.a11y_watchdog_stale_text),
                    accessibilitySettingsIntent()
                )
            }
            else -> {
                nm.cancel(NOTIF_A11Y_OFF_ID)
                nm.cancel(NOTIF_A11Y_STALE_ID)
            }
        }
    }

    private fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        // Diagnostics prefs shared with the Home "সার্ভিস স্ট্যাটাস" card and the
        // accessibility service: one file, no new dependency.
        const val DIAG_PREFS = "diag"
        const val KEY_LAST_KEEPALIVE_TICK = "last_keepalive_tick"
        const val KEY_LAST_A11Y_EVENT = "last_a11y_event"
        const val KEY_LAST_A11Y_PKG = "last_a11y_pkg"
        /** Most recent window-state event, short "pkg/Class · STATE" form. */
        const val KEY_LAST_EVENT = "last_event"

        /** Accessibility watchdog state (see checkA11yWatchdog): "on" /
         *  "off" / "stale". Written by the tick; the Home card reads it. */
        const val KEY_A11Y_STATE = "a11y_state"

        /** Last uncaught crash, one short line "<Exception>: <first message
         *  line>" + when it happened. Written by the crash recorder
         *  installed in PureShieldApp.onCreate; the Home card's 6th row
         *  reads it. Never a full stack trace, never user data. */
        const val KEY_LAST_CRASH = "last_crash"
        const val KEY_LAST_CRASH_AT = "last_crash_at"
        const val A11Y_STATE_ON = "on"
        const val A11Y_STATE_OFF = "off"
        const val A11Y_STATE_STALE = "stale"

        /** No window-state event for this long while accessibility is on
         *  in Settings = "not responding". */
        private const val A11Y_STALE_MS = 3 * 60_000L
        /** A "stale" candidate must persist this long before it counts, so
         *  turning accessibility on does not flash a false alert. */
        private const val A11Y_STALE_CONFIRM_MS = 60_000L
        private const val KEY_A11Y_STALE_SINCE = "a11y_stale_since"
        /** Notification ids for the watchdog alerts (2001-2006 are the
         *  permission prompts, 2100 is the ongoing lock notification). */
        private const val NOTIF_A11Y_OFF_ID = 2101
        private const val NOTIF_A11Y_STALE_ID = 2102

        /**
         * Derives the accessibility state from the Settings check + the age
         * of the last window-state event: "off" when the service is not
         * enabled in Settings, "stale" when it is enabled but no event
         * arrived within [A11Y_STALE_MS], "on" otherwise. Pure (no writes),
         * so the Home card can use it as a live fallback too.
         */
        fun deriveA11yState(context: Context): String {
            if (!PermissionPrompter.isAccessibilityEnabled(context)) return A11Y_STATE_OFF
            val lastEvent = context.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_A11Y_EVENT, 0L)
            return if (lastEvent <= 0L ||
                System.currentTimeMillis() - lastEvent > A11Y_STALE_MS
            ) {
                A11Y_STATE_STALE
            } else {
                A11Y_STATE_ON
            }
        }

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
