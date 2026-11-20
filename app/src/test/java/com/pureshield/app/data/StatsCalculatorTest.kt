package com.pureshield.app.data

import com.pureshield.app.data.db.FocusDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsCalculatorTest {

    private val today = LocalDate.of(2026, 11, 19)

    private fun row(date: String, minutes: Long, blocks: Int = 0) =
        FocusDay(date = date, focusMinutes = minutes, blocks = blocks)

    @Test
    fun `empty data gives zeros and not invented numbers`() {
        val s = StatsCalculator.summarize(emptyList(), today)
        assertEquals(0L, s.totalMinutes)
        assertEquals(0L, s.todayMinutes)
        assertEquals(0, s.streakDays)
        assertEquals(0, s.blocks)
        assertEquals(7, s.last7Days.size)
        assertEquals(30, s.last30Days.size)
        assertTrue(s.last7Days.all { it.second == 0L })
        assertTrue(s.last30Days.all { it.second == 0L })
    }

    @Test
    fun `totals windows and counts add up`() {
        val rows = listOf(
            row("2026-11-19", 60, blocks = 3),
            row("2026-11-18", 120, blocks = 1),
            row("2026-11-17", 0),
            row("2026-11-15", 30),
            row("2026-10-01", 60) // outside the 30-day window
        )
        val s = StatsCalculator.summarize(rows, today)
        assertEquals(60 + 120 + 30 + 60, s.totalMinutes)
        assertEquals(60, s.todayMinutes)
        assertEquals(60 + 120 + 30, s.weekMinutes)
        assertEquals(60 + 120 + 30, s.monthMinutes)
        assertEquals(4, s.blocks)
    }

    @Test
    fun `last seven days end today and are in order`() {
        val s = StatsCalculator.summarize(listOf(row("2026-11-19", 60)), today)
        assertEquals(LocalDate.of(2026, 11, 13), s.last7Days.first().first)
        assertEquals(today, s.last7Days.last().first)
        assertEquals(60, s.last7Days.last().second)
    }

    @Test
    fun `last thirty days end today`() {
        val s = StatsCalculator.summarize(listOf(row("2026-11-19", 60)), today)
        assertEquals(LocalDate.of(2026, 10, 21), s.last30Days.first().first)
        assertEquals(today, s.last30Days.last().first)
    }

    @Test
    fun `streak counts consecutive protected days`() {
        val rows = listOf(row("2026-11-19", 60), row("2026-11-18", 60), row("2026-11-17", 0), row("2026-11-16", 60))
        assertEquals(2, StatsCalculator.summarize(rows, today).streakDays)
    }

    @Test
    fun `an empty today does not break the streak yet`() {
        val rows = listOf(row("2026-11-18", 60), row("2026-11-17", 60))
        assertEquals(2, StatsCalculator.summarize(rows, today).streakDays)
    }

    @Test
    fun `a missed day resets the streak`() {
        val rows = listOf(row("2026-11-17", 60), row("2026-11-16", 60))
        assertEquals(0, StatsCalculator.summarize(rows, today).streakDays)
    }

    @Test
    fun `malformed date keys are ignored instead of crashing`() {
        val s = StatsCalculator.summarize(listOf(row("garbage", 60), row("2026-11-19", 60)), today)
        assertEquals(60, s.todayMinutes)
    }
}
