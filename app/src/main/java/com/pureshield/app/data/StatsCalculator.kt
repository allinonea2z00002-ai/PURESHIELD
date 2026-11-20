package com.pureshield.app.data

import com.pureshield.app.data.db.FocusDay
import java.time.LocalDate

data class StatsSummary(
    /** Total protected minutes across all stored days. */
    val totalMinutes: Long,
    val todayMinutes: Long,
    val weekMinutes: Long,
    val monthMinutes: Long,
    val streakDays: Int,
    val blocks: Int,
    /** Oldest -> newest, always 7 entries ending today. */
    val last7Days: List<Pair<LocalDate, Long>>,
    /** Oldest -> newest, always 30 entries ending today. */
    val last30Days: List<Pair<LocalDate, Long>>
)

/** Pure computation over stored rows, so it can be unit-tested. */
object StatsCalculator {

    fun summarize(rows: List<FocusDay>, today: LocalDate): StatsSummary {
        val byDate: Map<LocalDate, FocusDay> =
            rows.mapNotNull { r -> runCatching { LocalDate.parse(r.date) to r }.getOrNull() }.toMap()

        fun sumLastDays(days: Long): Long =
            (0 until days).sumOf { byDate[today.minusDays(it)]?.focusMinutes ?: 0L }

        val last7 = (6 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            d to (byDate[d]?.focusMinutes ?: 0L)
        }
        val last30 = (29 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            d to (byDate[d]?.focusMinutes ?: 0L)
        }

        return StatsSummary(
            totalMinutes = rows.sumOf { it.focusMinutes },
            todayMinutes = byDate[today]?.focusMinutes ?: 0L,
            weekMinutes = sumLastDays(7),
            monthMinutes = sumLastDays(30),
            streakDays = streak(byDate, today),
            blocks = rows.sumOf { it.blocks },
            last7Days = last7,
            last30Days = last30
        )
    }

    /** Consecutive protected days. Today does not break the streak until the day is over. */
    fun streak(byDate: Map<LocalDate, FocusDay>, today: LocalDate): Int {
        var day = if ((byDate[today]?.focusMinutes ?: 0L) > 0) today else today.minusDays(1)
        var count = 0
        while ((byDate[day]?.focusMinutes ?: 0L) > 0) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
