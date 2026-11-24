package com.pureshield.app.ui.stats

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.StatsCalculator
import com.pureshield.app.data.db.BlockEvent
import com.pureshield.app.data.db.FocusDay
import com.pureshield.app.databinding.ActivityStatsBinding
import com.pureshield.app.databinding.ItemStatRowBinding
import com.pureshield.app.ui.BottomNav
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.ui.widget.BarChartView
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * Statistics screen (demo Screen 5, verbatim labels).
 *
 * Everything is real: the streak comes from [FocusDay] rows via
 * [StatsCalculator] (focus is stored in MINUTES), the bars are real daily
 * focus time, the footer totals are computed from the same rows, and
 * "সবচেয়ে বেশি ব্লক হয়েছে" aggregates real [BlockEvent] rows. At first
 * run the streak is honestly ০ days and the chart shows the empty state —
 * never the demo's hard-coded 6/11 days, 2.5–3.9h bars or ৩৪×/২৭×/২১×/১৬×
 * top list.
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var b: ActivityStatsBinding

    private var days: List<FocusDay> = emptyList()
    private var events: List<BlockEvent> = emptyList()
    private var rangeDays: Int = 7

    private val topAdapter = TopAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(b.root)
        BottomNav.bind(this, BottomNav.Tab.STATS)

        b.rvTop.layoutManager = LinearLayoutManager(this)
        b.rvTop.adapter = topAdapter

        b.toggleRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            rangeDays = if (checkedId == R.id.btnRange30) 30 else 7
            render()
        }

        b.btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    Graph.db.focusDayDao().observeAll(),
                    Graph.db.blockEventDao().observeRecent(500)
                ) { dayRows, eventRows -> Pair(dayRows, eventRows) }
                    .collect { (dayRows, eventRows) ->
                        days = dayRows
                        events = eventRows
                        render()
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.blurBackground.startMotion()
    }

    override fun onPause() {
        b.blurBackground.stopMotion()
        super.onPause()
    }

    private fun render() {
        val today = LocalDate.now()
        val summary = StatsCalculator.summarize(days, today)
        val best = bestStreak(days)
        b.tvStreakDays.text = getString(
            R.string.stats_streak_days,
            BnDigits.toLocalizedDigits(summary.streakDays.toString())
        )
        b.tvStreakSub.text = getString(
            R.string.stats_streak_sub,
            BnDigits.toLocalizedDigits(summary.streakDays.toString()),
            BnDigits.toLocalizedDigits(best.toString())
        )

        // Bars: minutes from StatsCalculator (7 or 30 entries ending today).
        val dayPairs = if (rangeDays == 30) summary.last30Days else summary.last7Days
        val bars = ArrayList<BarChartView.Bar>(rangeDays)
        var totalMinutes = 0L
        var anyData = false
        for ((date, minutes) in dayPairs) {
            totalMinutes += minutes
            if (minutes > 0) anyData = true
            val label = if (rangeDays == 7) {
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, BnDigits.appLocale())
            } else {
                val offset = today.toEpochDay() - date.toEpochDay()
                if (offset % 5 == 0L) BnDigits.toLocalizedDigits(date.dayOfMonth.toString()) else ""
            }
            bars.add(BarChartView.Bar(label, minutes * 60_000L))
        }
        b.chartBars.showValues = rangeDays == 7
        b.chartBars.setBars(bars)
        b.tvChartEmpty.isVisible = !anyData

        val fromDate = today.minusDays((rangeDays - 1).toLong())
        val totalBlocks = days
            .filter { row ->
                runCatching { LocalDate.parse(row.date) }.getOrNull()
                    ?.let { !it.isBefore(fromDate) && !it.isAfter(today) } == true
            }
            .sumOf { it.blocks }
        val avgMinutes = if (rangeDays > 0) totalMinutes / rangeDays else 0L
        b.tvTotalFocus.text = getString(
            R.string.stats_total_focus, BnDigits.formatMinutesLocalized(totalMinutes)
        )
        b.tvDailyAvg.text = getString(
            R.string.stats_daily_avg, BnDigits.formatMinutesLocalized(avgMinutes)
        )
        b.tvTotalBlocks.text = getString(
            R.string.stats_total_blocks, BnDigits.toLocalizedDigits(totalBlocks.toString())
        )

        val top = events
            .groupingBy { it.packageNameOrHost }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .map { (key, count) ->
                val first = events.first { it.packageNameOrHost == key }
                TopRow(
                    label = labelFor(first),
                    detail = detailFor(first.kind),
                    count = count
                )
            }
        topAdapter.submit(top)
        b.rvTop.isVisible = top.isNotEmpty()
        b.tvTopEmpty.isVisible = top.isEmpty()
    }

    /** App blocks show the app label; sites/feeds/keywords show the host or word itself. */
    private fun labelFor(event: BlockEvent): String {
        if (event.kind != "APP") return event.packageNameOrHost
        return runCatching {
            val info = packageManager.getApplicationInfo(event.packageNameOrHost, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull() ?: event.packageNameOrHost
    }

    private fun detailFor(kind: String): String = when (kind) {
        "SITE" -> getString(R.string.stats_detail_site)
        "FEED" -> getString(R.string.stats_detail_reels)
        "KEYWORD" -> getString(R.string.stats_detail_keyword)
        else -> getString(R.string.stats_detail_app)
    }

    /** Longest run of consecutive days with focusMinutes > 0 (personal best). */
    private fun bestStreak(rows: List<FocusDay>): Int {
        val active = rows.filter { it.focusMinutes > 0 }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSortedSet()
        var best = 0
        var current = 0
        var prev: LocalDate? = null
        for (date in active) {
            current = if (prev != null && prev.plusDays(1) == date) current + 1 else 1
            if (current > best) best = current
            prev = date
        }
        return best
    }

    data class TopRow(val label: String, val detail: String, val count: Int)

    private inner class TopAdapter : RecyclerView.Adapter<TopAdapter.Holder>() {

        private var rows: List<TopRow> = emptyList()

        fun submit(newRows: List<TopRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemStatRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val vb: ItemStatRowBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(row: TopRow) {
                vb.tvName.text = row.label
                vb.tvDetail.text = row.detail
                vb.tvCount.text = getString(
                    R.string.stats_top_times,
                    BnDigits.toLocalizedDigits(row.count.toString())
                )
            }
        }
    }
}
