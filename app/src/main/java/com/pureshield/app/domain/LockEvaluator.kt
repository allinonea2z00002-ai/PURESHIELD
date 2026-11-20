package com.pureshield.app.domain

import com.pureshield.app.data.db.LockState

/** Pure lock rules. No Android dependencies, so they are covered by plain unit tests. */
object LockEvaluator {

    /** Preset time locks from the demo: 1, 10, 30 and 60 minutes. */
    val TIME_PRESETS_MINUTES: List<Long> = listOf(1L, 10L, 30L, 60L)

    val TIME_PRESETS_MS: List<Long> = TIME_PRESETS_MINUTES.map { it * MIN }

    /** A custom time must be at least 3 minutes and at most 24 hours (1440 minutes). */
    const val MIN_CUSTOM_MINUTES = 3L
    const val MAX_CUSTOM_MINUTES = 1440L

    /** Fixes worse than this are ignored when deciding presence. */
    const val MAX_ACCEPTED_ACCURACY_M = 200f

    /**
     * Time left, given a checkpoint.
     *
     * Same boot: use the monotonic clock (elapsedRealtime), which keeps counting in deep sleep and
     * cannot be changed by the user, so editing the system clock does not shorten the lock.
     * After a reboot the monotonic clock restarted, so the wall clock is used for the time the
     * device was off. A clock moved backwards never *adds* time and never goes negative.
     */
    fun remainingMs(
        remainingAtCheckpointMs: Long,
        checkpointElapsed: Long,
        checkpointEpoch: Long,
        nowElapsed: Long,
        nowEpoch: Long,
        sameBoot: Boolean
    ): Long {
        val passed = passedMs(checkpointElapsed, checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        return (remainingAtCheckpointMs - passed).coerceAtLeast(0L)
    }

    fun passedMs(
        checkpointElapsed: Long,
        checkpointEpoch: Long,
        nowElapsed: Long,
        nowEpoch: Long,
        sameBoot: Boolean
    ): Long {
        val raw = if (sameBoot) nowElapsed - checkpointElapsed else nowEpoch - checkpointEpoch
        return raw.coerceAtLeast(0L)
    }

    /** True when the stored boot counter says "same boot"; falls back to the monotonic clock when unknown. */
    fun isSameBoot(storedBootCount: Int, currentBootCount: Int, checkpointElapsed: Long, nowElapsed: Long): Boolean =
        if (storedBootCount >= 0 && currentBootCount >= 0) storedBootCount == currentBootCount
        else nowElapsed >= checkpointElapsed

    /**
     * Unlock rule for PURESHIELD: time OR distance, whichever happens first.
     * "Distance" here means the location condition was satisfied — the user left the saved
     * place (for a lock-while-inside zone) or entered it (for a lock-while-away zone).
     */
    fun isUnlocked(timeSatisfied: Boolean, distanceSatisfied: Boolean): Boolean =
        timeSatisfied || distanceSatisfied

    fun isValidMinutes(minutes: Long): Boolean =
        minutes in TIME_PRESETS_MINUTES || minutes in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES

    fun isValidCustomMinutes(minutes: Long) = minutes in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES

    /** Read-only view of a stored lock state, used by the UI ticker (no database writes). */
    fun snapshot(
        state: LockState?,
        nowElapsed: Long,
        nowEpoch: Long,
        currentBootCount: Int
    ): LockSnapshot {
        if (state == null) return LockSnapshot.INACTIVE
        val sameBoot = isSameBoot(state.bootCount, currentBootCount, state.checkpointElapsed, nowElapsed)
        val remaining = if (state.active) {
            remainingMs(
                state.remainingAtCheckpointMs, state.checkpointElapsed, state.checkpointEpoch,
                nowElapsed, nowEpoch, sameBoot
            )
        } else {
            0L
        }
        return LockSnapshot(
            active = state.active,
            locProtecting = state.locProtecting,
            durationMs = state.durationMs,
            remainingMs = remaining,
            timeSatisfied = state.timeSatisfied || (state.active && remaining == 0L),
            distanceSatisfied = state.distanceSatisfied
        )
    }

    private const val MIN = 60_000L
}
