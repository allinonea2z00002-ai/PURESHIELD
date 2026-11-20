package com.pureshield.app.data

import com.pureshield.app.data.db.FocusDay
import com.pureshield.app.data.db.FocusDayDao
import com.pureshield.app.util.DayUtils

/** Writes only real, observed events into the per-day statistics table. */
class StatsRepository(private val dao: FocusDayDao) {

    private suspend fun ensure(key: String) = dao.ensureRow(FocusDay(date = key))

    /** Credits protected time, splitting it correctly across midnight. */
    suspend fun addProtected(startEpoch: Long, durationMs: Long) {
        if (durationMs <= 0) return
        // FocusDay stores whole minutes; sub-minute remainders are dropped (floored, never
        // rounded up) so statistics never claim time that was not actually protected.
        for ((key, ms) in DayUtils.splitByDay(startEpoch, startEpoch + durationMs)) {
            ensure(key)
            dao.addFocusMinutes(key, ms / 60_000L)
        }
    }

    /** One block (app or site) recorded today. */
    suspend fun recordBlock(now: Long = System.currentTimeMillis()) {
        val key = DayUtils.dateKey(now)
        ensure(key)
        dao.addBlocks(key, 1)
    }

    suspend fun recordWebsiteBlock(now: Long = System.currentTimeMillis()) = recordBlock(now)

    suspend fun recordAppBlock(now: Long = System.currentTimeMillis()) = recordBlock(now)

    suspend fun setGoalMet(date: String, met: Boolean) {
        ensure(date)
        dao.setGoalMet(date, met)
    }
}
