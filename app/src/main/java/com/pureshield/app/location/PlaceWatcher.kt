package com.pureshield.app.location

import android.content.Context
import android.util.Log
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.domain.LockEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Presence checking for the location lock.
 *
 * Runs in two ways:
 * - [PlaceWatcherWorker] (WorkManager, every 15 min) — the reliable background path.
 * - [startForegroundLoop] — a 90-second loop while the location lock is on and the app's
 *   foreground service is alive, so a boundary is noticed within 1–2 minutes.
 *
 * Rules (from the demo / PowerLock design):
 * - Fixes worse than 200 m are ignored.
 * - Enter a zone at d <= r, exit at d > r * 1.3 (hysteresis, so GPS jitter at the edge does
 *   not flap the lock).
 * - A zone protects while the user is inside it (modeInside) or while outside it (lock-while-away).
 * - On a crossing, a ZoneEvent row is written and — when the boundary alert is on — a
 *   notification is posted. When the user comes within 10 m of a boundary without crossing,
 *   a "boundary near" alert is posted (at most once per 30 minutes per zone).
 * - The location-lock flag in LockManager is set from presence only; there are no sessions.
 * - Places without saved coordinates (templates such as বাড়ি / বাজার before the user taps
 *   them on the map) never drive the lock.
 * - Background location denied: the check is skipped, a warning is logged and the user is
 *   notified once (the lock cannot fire with the screen off without "Allow all the time").
 */
object PlaceWatcher {

    private const val TAG = "PlaceWatcher"
    private const val LOOP_INTERVAL_MS = 90_000L
    private const val BOUNDARY_NEAR_M = 10f
    private const val NEAR_ALERT_MIN_INTERVAL_MS = 30 * 60_000L

    private var loopJob: Job? = null
    private val lastNearAlertAt = HashMap<Long, Long>()
    private var lastDeniedNotifyAt = 0L

    /** Starts the 1–2 minute foreground check loop (no-op when already running). */
    fun startForegroundLoop(context: Context) {
        if (loopJob?.isActive == true) return
        loopJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                runCatching { checkNow(context.applicationContext) }
                delay(LOOP_INTERVAL_MS)
            }
        }
    }

    fun stopForegroundLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** One presence check: updates zones, ZoneEvents, alerts and the LockManager flag. */
    suspend fun checkNow(context: Context) {
        val settings = Graph.settings.get()
        if (!settings.locationLockEnabled) {
            Graph.lockManager.clearLocProtecting()
            return
        }
        if (!LocationHelper.hasForegroundPermission(context)) {
            Log.w(TAG, "Location permission missing; location lock cannot check presence")
            return
        }
        if (!LocationHelper.hasBackgroundPermission(context)) {
            val now = System.currentTimeMillis()
            if (now - lastDeniedNotifyAt > 24 * 60 * 60_000L) {
                lastDeniedNotifyAt = now
                Log.w(TAG, "Background location not granted; auto-lock may not fire with the screen off")
                Graph.notifier.alert(
                    com.pureshield.app.util.Notifier.ID_LOCATION_DENIED,
                    context.getString(R.string.notif_location_denied_title),
                    context.getString(R.string.notif_location_denied_text)
                )
            }
            // Still check with foreground permission while the app is alive; the worker
            // cannot do this in the background, so presence may go stale when the app dies.
        }
        val fix = LocationHelper.currentFix(context, highAccuracy = false, timeoutMs = 20_000)
            ?: return
        if (fix.accuracyM > LockEvaluator.MAX_ACCEPTED_ACCURACY_M) {
            Log.d(TAG, "Ignoring fix with accuracy ${fix.accuracyM}m")
            return
        }
        applyFix(context, fix)
    }

    /**
     * Applies one fix to all enabled zones. Pure presence logic, separated so the worker,
     * the foreground loop and tests share the same code path.
     */
    suspend fun applyFix(context: Context, fix: Fix) {
        val settings = Graph.settings.get()
        val zoneDao = Graph.db.safeZoneDao()
        val zones = zoneDao.getAll()
        var protectingNow = false
        val now = System.currentTimeMillis()

        for (zone in zones) {
            if (!zone.enabled) continue
            val lat = zone.lat ?: continue
            val lng = zone.lng ?: continue
            val d = LocationHelper.distanceMeters(lat, lng, fix.latitude, fix.longitude)
            val insideNow = d <= zone.radiusM
            val prev = zone.isInside
            // Hysteresis: enter at d <= r, exit only at d > r * 1.3.
            val inside = when (prev) {
                null -> insideNow
                true -> d <= zone.radiusM * 1.3f
                false -> insideNow
            }
            if (prev == null) {
                zoneDao.upsert(zone.copy(isInside = inside))
            } else if (inside != prev) {
                zoneDao.upsert(zone.copy(isInside = inside))
                Graph.db.zoneEventDao().insert(
                    com.pureshield.app.data.db.ZoneEvent(zoneId = zone.id, entered = inside, at = now)
                )
                if (settings.boundaryAlert) {
                    val title = context.getString(
                        if (inside) R.string.notif_zone_entered_title else R.string.notif_zone_left_title
                    )
                    Graph.notifier.alert(
                        com.pureshield.app.util.Notifier.ID_BOUNDARY,
                        title,
                        context.getString(
                            if (inside) R.string.notif_zone_entered_text else R.string.notif_zone_left_text,
                            zone.name
                        )
                    )
                }
            }

            // Boundary-near alert: within 10 m of the radius without crossing yet.
            if (settings.boundaryAlert && kotlin.math.abs(d - zone.radiusM) <= BOUNDARY_NEAR_M) {
                val last = lastNearAlertAt[zone.id] ?: 0L
                if (now - last > NEAR_ALERT_MIN_INTERVAL_MS) {
                    lastNearAlertAt[zone.id] = now
                    Graph.notifier.alert(
                        com.pureshield.app.util.Notifier.ID_BOUNDARY,
                        context.getString(R.string.notif_boundary_near_title),
                        context.getString(R.string.notif_boundary_near_text, zone.name, d.toInt())
                    )
                }
            }

            val zoneProtects = if (zone.modeInside) inside else !inside
            if (zoneProtects) protectingNow = true
        }

        Graph.lockManager.setLocProtecting(protectingNow)
    }
}
