package com.pureshield.app.domain

import com.pureshield.app.data.db.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockEvaluatorTest {

    private val hour = 3_600_000L

    // ---------------------------------------------------------------- time keeping

    @Test
    fun `same boot uses the monotonic clock`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 1_000,
            checkpointEpoch = 5_000_000,
            nowElapsed = 601_000,
            nowEpoch = 9_999_999_999,
            sameBoot = true
        )
        assertEquals(hour - 600_000, remaining)
    }

    @Test
    fun `moving the wall clock backwards does not add time`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 0,
            checkpointEpoch = 1_000_000_000,
            nowElapsed = 10_000,
            nowEpoch = 1_000_000_000 - 86_400_000, // user set the date back one day
            sameBoot = true
        )
        assertEquals(hour - 10_000, remaining)
    }

    @Test
    fun `moving the wall clock forwards does not shorten the lock`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 0,
            checkpointEpoch = 1_000_000_000,
            nowElapsed = 10_000,
            nowEpoch = 1_000_000_000 + 30L * 86_400_000, // user set the date a month ahead
            sameBoot = true
        )
        assertEquals(hour - 10_000, remaining)
    }

    @Test
    fun `after a reboot the wall clock is used for the time the device was off`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 999_999_999, // from before the reboot, meaningless now
            checkpointEpoch = 1_000_000,
            nowElapsed = 5_000,
            nowEpoch = 1_000_000 + 120_000,
            sameBoot = false
        )
        assertEquals(hour - 120_000, remaining)
    }

    @Test
    fun `after a reboot a wall clock set backwards never adds time`() {
        val remaining = LockEvaluator.remainingMs(hour, 0, 1_000_000, 5_000, 1_000_000 - 500_000, sameBoot = false)
        assertEquals(hour, remaining)
    }

    @Test
    fun `remaining time never goes below zero`() {
        val remaining = LockEvaluator.remainingMs(1_000, 0, 0, 50_000, 50_000, sameBoot = true)
        assertEquals(0L, remaining)
    }

    @Test
    fun `boot detection`() {
        assertTrue(LockEvaluator.isSameBoot(5, 5, 100, 500))
        assertFalse(LockEvaluator.isSameBoot(5, 6, 100, 500))
        // boot counter unavailable: fall back to "the monotonic clock did not go backwards"
        assertFalse(LockEvaluator.isSameBoot(-1, -1, 100_000, 500))
        assertTrue(LockEvaluator.isSameBoot(-1, -1, 100, 500))
    }

    // ---------------------------------------------------------------- unlock rule: time OR distance

    @Test
    fun `unlock needs either time or distance`() {
        assertFalse(LockEvaluator.isUnlocked(timeSatisfied = false, distanceSatisfied = false))
        assertTrue(LockEvaluator.isUnlocked(timeSatisfied = true, distanceSatisfied = false))
        assertTrue(LockEvaluator.isUnlocked(timeSatisfied = false, distanceSatisfied = true))
        assertTrue(LockEvaluator.isUnlocked(timeSatisfied = true, distanceSatisfied = true))
    }

    // ---------------------------------------------------------------- validation

    @Test
    fun `custom duration limits`() {
        assertFalse(LockEvaluator.isValidCustomMinutes(2))
        assertTrue(LockEvaluator.isValidCustomMinutes(3))
        assertTrue(LockEvaluator.isValidCustomMinutes(5))
        assertTrue(LockEvaluator.isValidCustomMinutes(1440))
        assertFalse(LockEvaluator.isValidCustomMinutes(1441))
    }

    @Test
    fun `presets are allowed even below the custom minimum`() {
        assertTrue(LockEvaluator.isValidMinutes(1)) // 1-minute preset
        assertTrue(LockEvaluator.isValidMinutes(10))
        assertTrue(LockEvaluator.isValidMinutes(30))
        assertTrue(LockEvaluator.isValidMinutes(60))
        assertFalse(LockEvaluator.isValidMinutes(2)) // neither a preset nor a valid custom time
        assertFalse(LockEvaluator.isValidMinutes(0))
        assertFalse(LockEvaluator.isValidMinutes(1441))
    }

    @Test
    fun `presets match the demo`() {
        assertEquals(listOf(1L, 10L, 30L, 60L), LockEvaluator.TIME_PRESETS_MINUTES)
        assertEquals(4, LockEvaluator.TIME_PRESETS_MS.size)
        assertEquals(1L * 60_000, LockEvaluator.TIME_PRESETS_MS.first())
        assertEquals(60L * 60_000, LockEvaluator.TIME_PRESETS_MS.last())
    }

    // ---------------------------------------------------------------- snapshot

    private fun timeState(remaining: Long = hour, bootCount: Int = 1) = LockState(
        active = true,
        durationMs = hour,
        remainingAtCheckpointMs = remaining,
        checkpointElapsed = 0,
        checkpointEpoch = 0,
        bootCount = bootCount
    )

    @Test
    fun `no state means inactive`() {
        assertFalse(LockEvaluator.snapshot(null, 0, 0, 1).active)
        assertFalse(LockEvaluator.snapshot(timeState().copy(active = false), 0, 0, 1).active)
    }

    @Test
    fun `snapshot reports remaining time and progress`() {
        val snap = LockEvaluator.snapshot(timeState(), nowElapsed = hour / 2, nowEpoch = hour / 2, currentBootCount = 1)
        assertTrue(snap.active)
        assertTrue(snap.protecting)
        assertEquals(hour / 2, snap.remainingMs)
        assertFalse(snap.timeSatisfied)
        assertEquals(0.5f, snap.progress, 0.001f)
    }

    @Test
    fun `snapshot marks time satisfied when the countdown reaches zero`() {
        val snap = LockEvaluator.snapshot(timeState(remaining = 1_000), nowElapsed = 5_000, nowEpoch = 5_000, currentBootCount = 1)
        assertEquals(0L, snap.remainingMs)
        assertTrue(snap.timeSatisfied)
    }

    @Test
    fun `location-only protection has no timer progress`() {
        val snap = LockEvaluator.snapshot(
            LockState(active = false, locProtecting = true), nowElapsed = 0, nowEpoch = 0, currentBootCount = 1
        )
        assertFalse(snap.active)
        assertTrue(snap.locProtecting)
        assertTrue(snap.protecting)
        assertEquals(0f, snap.progress, 0.001f)
    }
}
