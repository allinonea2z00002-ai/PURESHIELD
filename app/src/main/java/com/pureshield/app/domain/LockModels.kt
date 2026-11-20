package com.pureshield.app.domain

/** Read-only facts the unlock rule needs from the location side. */
data class LocationFacts(
    /** True when the user is currently inside at least one enabled SafeZone that protects. */
    val insideAnyZone: Boolean
)

/** What the UI shows for the current lock. Computed from stored state; never from fake values. */
data class LockSnapshot(
    val active: Boolean,
    /** True while the location lock is protecting (presence-based, no session). */
    val locProtecting: Boolean,
    val durationMs: Long,
    val remainingMs: Long,
    val timeSatisfied: Boolean,
    val distanceSatisfied: Boolean
) {
    /** True while any protection is active: a time session OR the location lock. */
    val protecting: Boolean get() = active || locProtecting

    /** 0f..1f progress toward the time unlock, for the countdown ring. 0 when there is no timer. */
    val progress: Float
        get() = if (durationMs > 0) {
            (1f - remainingMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    companion object {
        val INACTIVE = LockSnapshot(
            active = false, locProtecting = false, durationMs = 0, remainingMs = 0,
            timeSatisfied = false, distanceSatisfied = false
        )
    }
}

/** Why a lock ended. */
enum class LockEndReason {
    COMPLETED,
    EMERGENCY_OVERRIDE,
    DISTANCE
}
