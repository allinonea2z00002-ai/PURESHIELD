package com.pureshield.app.domain

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.pureshield.app.R
import com.pureshield.app.data.StatsRepository
import com.pureshield.app.data.db.AppDatabase
import com.pureshield.app.data.db.LockState
import com.pureshield.app.data.prefs.SecureStore
import com.pureshield.app.data.prefs.SettingsStore
import com.pureshield.app.service.KeepAliveService
import com.pureshield.app.service.WorkScheduler
import com.pureshield.app.util.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The lock engine. All state lives in the database (see [LockState]); nothing is held only in
 * memory, so the lock survives app closure, process death, screen-off and reboot.
 *
 * Two independent protections:
 * - Time lock: a session with a countdown. Started with [start], extended only (never reduced).
 * - Location lock: the [LockState.locProtecting] flag, set from presence by PlaceWatcher.
 *   No sessions, no countdown — at a saved place with location on, the power menu is blocked;
 *   leaving opens it.
 *
 * Unlock rule: time OR distance, whichever happens first. "Distance" is satisfied when the
 * user leaves the protecting zone while a time session is running (see [setLocProtecting]).
 *
 * [protecting] = time session active OR location flag on. That is the single predicate the
 * accessibility service and the guards use.
 */
class LockManager(
    private val context: Context,
    private val db: AppDatabase,
    private val stats: StatsRepository,
    private val notifier: Notifier,
    private val settings: SettingsStore,
    private val secure: SecureStore
) {
    private val mutex = Mutex()
    private val dao get() = db.lockStateDao()

    private val _state = MutableStateFlow(LockSnapshot.INACTIVE)
    /** Latest snapshot. The UI ticks every 500 ms and reads [remainingMs] for the countdown. */
    val state: StateFlow<LockSnapshot> = _state.asStateFlow()

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyActive : StartResult
        data object InvalidDuration : StartResult
    }

    sealed interface ExtendResult {
        data object Extended : ExtendResult
        data object NotActive : ExtendResult
        data object WouldReduce : ExtendResult
        data object InvalidDuration : ExtendResult
    }

    fun observe(): Flow<LockState?> = dao.observe()

    suspend fun current(): LockState? = dao.get()

    /** True while any protection is active: a time session OR the location lock. */
    suspend fun protecting(): Boolean {
        val s = dao.get() ?: return false
        return s.active || s.locProtecting
    }

    suspend fun isLocked(): Boolean = protecting()

    /** Remaining milliseconds of the time session, 0 when there is no active session. */
    suspend fun remainingMs(): Long {
        val s = dao.get() ?: return 0L
        if (!s.active) return 0L
        return LockEvaluator.remainingMs(
            s.remainingAtCheckpointMs, s.checkpointElapsed, s.checkpointEpoch,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            LockEvaluator.isSameBoot(s.bootCount, bootCount(), s.checkpointElapsed, SystemClock.elapsedRealtime())
        )
    }

    /** Current snapshot without writing anything (used by the UI ticker). */
    suspend fun snapshotNow(): LockSnapshot {
        val s = dao.get()
        return LockEvaluator.snapshot(s, SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount())
    }

    fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    // ---------------------------------------------------------------- start / extend

    suspend fun start(minutes: Long): StartResult = mutex.withLock {
        val existing = dao.get()
        if (existing?.active == true) return@withLock StartResult.AlreadyActive
        if (!LockEvaluator.isValidMinutes(minutes)) return@withLock StartResult.InvalidDuration

        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val durationMs = minutes * 60_000L
        val state = LockState(
            active = true,
            durationMs = durationMs,
            startEpoch = now,
            remainingAtCheckpointMs = durationMs,
            checkpointElapsed = elapsed,
            checkpointEpoch = now,
            bootCount = bootCount(),
            locProtecting = existing?.locProtecting ?: false
        )
        dao.upsert(state)
        refreshStateLocked()
        WorkScheduler.scheduleUnlock(context, durationMs)
        WorkScheduler.schedulePeriodic(context)
        // A lock with no foreground service dies on a Recents swipe: the
        // process is killed, the accessibility service dies with it, and the
        // restart logic never runs because it lives in this service. Start
        // it here so protection actually survives the swipe.
        KeepAliveService.start(context)
        StartResult.Started
    }

    /**
     * Extends the running lock by [minutes]. Only ever adds time: asking for less than the
     * remaining time is refused ([ExtendResult.WouldReduce]) instead of silently shortening.
     */
    suspend fun extend(minutes: Long): ExtendResult = mutex.withLock {
        val cfg = dao.get() ?: return@withLock ExtendResult.NotActive
        if (!cfg.active) return@withLock ExtendResult.NotActive
        if (!LockEvaluator.isValidMinutes(minutes)) return@withLock ExtendResult.InvalidDuration
        // Extend-only: the caller passes the extra minutes to add, so the result can only grow.
        // (The UI offers "add 10 / 30 / 60 minutes", never "set to X".)
        if (minutes <= 0) return@withLock ExtendResult.WouldReduce
        val remaining = LockEvaluator.remainingMs(
            cfg.remainingAtCheckpointMs, cfg.checkpointElapsed, cfg.checkpointEpoch,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            LockEvaluator.isSameBoot(cfg.bootCount, bootCount(), cfg.checkpointElapsed, SystemClock.elapsedRealtime())
        )
        val updated = cfg.copy(
            durationMs = cfg.durationMs + minutes * 60_000L,
            remainingAtCheckpointMs = remaining + minutes * 60_000L,
            checkpointElapsed = SystemClock.elapsedRealtime(),
            checkpointEpoch = System.currentTimeMillis(),
            bootCount = bootCount()
        )
        dao.upsert(updated)
        refreshStateLocked()
        WorkScheduler.scheduleUnlock(context, updated.remainingAtCheckpointMs)
        ExtendResult.Extended
    }

    // ----------------------------------------------------------- location presence

    /**
     * Called by PlaceWatcher whenever presence changes. Sets the location-lock flag from
     * presence (no sessions). If a time session was running while the user was protected and
     * the user just left, the distance condition is satisfied: the lock ends (time OR
     * distance, whichever happens first).
     */
    suspend fun setLocProtecting(protecting: Boolean) = mutex.withLock {
        val cfg = dao.get() ?: LockState()
        val wasProtecting = cfg.locProtecting
        if (cfg.locProtecting == protecting) return@withLock
        var updated = cfg.copy(locProtecting = protecting)
        if (cfg.active && wasProtecting && !protecting) {
            // Left the place while a time lock was running: the distance condition is
            // satisfied ONLY when the Time Lock "combine with location" switch is on
            // (demo default ON). When it is off, only the timer can end the lock.
            if (settings.get().combineWithLocation) {
                updated = updated.copy(distanceSatisfied = true)
                dao.upsert(updated)
                checkpointLocked()
                return@withLock
            }
        }
        dao.upsert(updated)
        refreshStateLocked()
        // Location protection just turned on or off: keep the foreground
        // service in step, so the location lock also survives a swipe.
        if (updated.locProtecting) {
            KeepAliveService.start(context)
        } else if (!updated.active) {
            KeepAliveService.stop(context)
        }
    }

    /** Location lock master off / last place disabled or deleted: clears the flag. */
    suspend fun clearLocProtecting() = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.locProtecting) return@withLock
        dao.upsert(cfg.copy(locProtecting = false))
        refreshStateLocked()
        if (!cfg.active) KeepAliveService.stop(context)
    }

    // ------------------------------------------------------------- evaluation

    /**
     * Advances the checkpoint, credits protected time, latches time conditions and finishes the lock
     * when its unlock rule is satisfied. Safe to call at any moment and any number of times.
     */
    suspend fun checkpoint(): LockState? = mutex.withLock { checkpointLocked() }

    private suspend fun checkpointLocked(): LockState? {
        val cfg = dao.get() ?: return null
        if (!cfg.active) {
            refreshStateLocked()
            return cfg
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val sameBoot = LockEvaluator.isSameBoot(cfg.bootCount, bootCount(), cfg.checkpointElapsed, nowElapsed)
        val passed = LockEvaluator.passedMs(cfg.checkpointElapsed, cfg.checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        val remaining = (cfg.remainingAtCheckpointMs - passed).coerceAtLeast(0)
        // Never credit more protected time than the lock actually had left.
        val credited = minOf(passed, cfg.remainingAtCheckpointMs)
        stats.addProtected(nowEpoch - credited, credited)

        val updated = cfg.copy(
            remainingAtCheckpointMs = remaining,
            checkpointElapsed = nowElapsed,
            checkpointEpoch = nowEpoch,
            bootCount = bootCount(),
            timeSatisfied = cfg.timeSatisfied || remaining == 0L
        )
        return evaluateAndSave(updated)
    }

    private suspend fun evaluateAndSave(cfg: LockState): LockState {
        if (LockEvaluator.isUnlocked(cfg.timeSatisfied, cfg.distanceSatisfied)) {
            val reason = if (cfg.distanceSatisfied && !cfg.timeSatisfied) LockEndReason.DISTANCE
            else LockEndReason.COMPLETED
            return finishLocked(cfg, reason)
        }
        dao.upsert(cfg)
        refreshStateLocked()
        return cfg
    }

    // ---------------------------------------------------------------- finish

    /**
     * Ends the time lock at once. Always available and always logged: a lock must never trap
     * someone in a genuine emergency. The UI asks for an explicit confirmation first.
     * The location flag is NOT cleared here — it follows presence, not the time session.
     */
    suspend fun endLock(reason: LockEndReason = LockEndReason.COMPLETED) = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.active) return@withLock
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val sameBoot = LockEvaluator.isSameBoot(cfg.bootCount, bootCount(), cfg.checkpointElapsed, nowElapsed)
        val passed = LockEvaluator.passedMs(cfg.checkpointElapsed, cfg.checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        val credited = minOf(passed, cfg.remainingAtCheckpointMs)
        stats.addProtected(nowEpoch - credited, credited)
        finishLocked(cfg, reason)
    }

    /** Emergency unlock with the partner PIN. Returns false when no PIN is set or it is wrong. */
    suspend fun emergencyUnlock(pin: String): Boolean {
        if (!secure.hasPartnerPin() || !secure.verifyPartnerPin(pin)) return false
        endLock(LockEndReason.EMERGENCY_OVERRIDE)
        return true
    }

    private suspend fun finishLocked(cfg: LockState, reason: LockEndReason): LockState {
        val finished = cfg.copy(active = false, timeSatisfied = false, distanceSatisfied = false)
        dao.upsert(finished)
        refreshStateLocked()
        WorkScheduler.cancelUnlock(context)
        // Time session over: stop the keep-alive service unless the location
        // lock is still protecting (then it must keep running).
        if (!finished.locProtecting) KeepAliveService.stop(context)
        if (reason == LockEndReason.COMPLETED || reason == LockEndReason.DISTANCE) {
            notifier.event(
                Notifier.ID_UNLOCKED,
                context.getString(R.string.notif_completed_title),
                context.getString(R.string.notif_completed_text)
            )
        }
        return finished
    }

    // ------------------------------------------------------ system recovery

    /** Wall-clock or time-zone change. The monotonic clock is unaffected; just re-anchor. */
    suspend fun onSystemTimeChanged() {
        checkpoint()
    }

    /** After reboot or app update: re-anchor time and reschedule the unlock worker. */
    suspend fun recoverAfterRestart() {
        val cfg = checkpoint()
        if (cfg?.active == true) {
            val remaining = LockEvaluator.snapshot(
                cfg, SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount()
            ).remainingMs
            if (remaining > 0) WorkScheduler.scheduleUnlock(context, remaining)
        }
        WorkScheduler.schedulePeriodic(context)
    }

    private suspend fun refreshStateLocked() {
        _state.value = LockEvaluator.snapshot(
            dao.get(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount()
        )
    }

    /** Refreshes the exposed StateFlow after an outside change (e.g. a zone edit). */
    suspend fun refreshState() = mutex.withLock { refreshStateLocked() }
}
