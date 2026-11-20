package com.pureshield.app.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.StatsCalculator
import com.pureshield.app.data.db.FocusDay
import com.pureshield.app.data.prefs.AppSettings
import com.pureshield.app.databinding.ActivityMainBinding
import com.pureshield.app.databinding.ItemGuardRowBinding
import com.pureshield.app.databinding.ItemQuickActionBinding
import com.pureshield.app.databinding.ItemQuickActionWideBinding
import com.pureshield.app.domain.LockSnapshot
import com.pureshield.app.ui.blocking.BlockingActivity
import com.pureshield.app.ui.location.LocationActivity
import com.pureshield.app.ui.settings.SettingsActivity
import com.pureshield.app.ui.stats.StatsActivity
import com.pureshield.app.ui.timelock.TimeLockActivity
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Dashboard (demo Screen 1): header with Bengali clock, lock banner, the
 * breathing protection ring, real stat cards, quick actions, protection
 * guards and the theme hint. Every number comes from the engine — nothing
 * here is hard-coded demo data.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var shieldFloatAnimator: ObjectAnimator? = null
    private var bannerPulseAnimator: ObjectAnimator? = null
    private var guardRows: List<Pair<GuardRow, ItemGuardRowBinding>> = emptyList()

    private data class GuardRow(
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val subRes: Int,
        val isOn: (AppSettings) -> Boolean,
        val setOn: suspend (Boolean) -> Unit
    )

    private data class QuickAction(
        @DrawableRes val iconRes: Int,
        @StringRes val labelRes: Int,
        val onClick: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        BottomNav.bind(this, BottomNav.Tab.HOME)

        val theme = ThemeManager.current(this)
        b.statsGrid.columnCount = theme.statsColumns

        setupHeader()
        setupQuickActions(theme.quickActionColumns)
        buildGuards()
        startFloatAnimations()
        observeData()
        startTickers()
    }

    override fun onDestroy() {
        shieldFloatAnimator?.cancel()
        bannerPulseAnimator?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ header

    private fun setupHeader() {
        b.clockText.text = bengaliClock()

        b.themeButton.setOnClickListener {
            val next = ThemeManager.cycle(this)
            Toast.makeText(this, getString(R.string.home_theme_toast, next.displayNameBn), Toast.LENGTH_SHORT).show()
            recreate()
        }
        b.bellButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.guardSeeAll.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /** Live clock in Bengali digits, 12-hour (e.g. "৯:৪১"). */
    private fun bengaliClock(): String {
        val now = LocalTime.now()
        val hour12 = when (val h = now.hour % 12) {
            0 -> 12
            else -> h
        }
        return BnDigits.toBnDigits("%d:%02d".format(hour12, now.minute))
    }

    // ------------------------------------------------------------ quick actions

    private fun setupQuickActions(columns: Int) {
        val items = listOf(
            QuickAction(R.drawable.ic_timer, R.string.home_quick_time_lock) {
                startActivity(Intent(this, TimeLockActivity::class.java))
            },
            QuickAction(R.drawable.ic_pin, R.string.home_quick_location) {
                startActivity(Intent(this, LocationActivity::class.java))
            },
            QuickAction(R.drawable.ic_list, R.string.home_quick_block_list) {
                startActivity(Intent(this, BlockingActivity::class.java))
            },
            QuickAction(R.drawable.ic_assessment, R.string.home_quick_report) {
                startActivity(Intent(this, StatsActivity::class.java))
            }
        )
        b.quickGrid.layoutManager = GridLayoutManager(this, columns)
        b.quickGrid.adapter = QuickAdapter(items, wide = columns == 2)
    }

    private inner class QuickAdapter(
        private val items: List<QuickAction>,
        private val wide: Boolean
    ) : RecyclerView.Adapter<QuickAdapter.Holder>() {

        inner class Holder(val root: View, val icon: AppCompatImageView, val label: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            return if (wide) {
                val binding = ItemQuickActionWideBinding.inflate(inflater, parent, false)
                Holder(binding.root, binding.qaIcon, binding.qaLabel)
            } else {
                val binding = ItemQuickActionBinding.inflate(inflater, parent, false)
                Holder(binding.root, binding.qaIcon, binding.qaLabel)
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.iconRes)
            holder.label.setText(item.labelRes)
            holder.root.setOnClickListener { item.onClick() }
        }

        override fun getItemCount(): Int = items.size
    }

    // ------------------------------------------------------------------- guards

    private fun buildGuards() {
        val guards = listOf(
            GuardRow(
                R.drawable.ic_power, R.string.guard_power_title, R.string.guard_power_sub,
                { it.powerMenuBlock },
                { Graph.settings.setPowerMenuBlock(it) }
            ),
            GuardRow(
                R.drawable.ic_delete, R.string.guard_uninstall_title, R.string.guard_uninstall_sub,
                { it.uninstallProtection },
                { Graph.settings.setUninstallProtection(it) }
            ),
            GuardRow(
                R.drawable.ic_verified, R.string.guard_strict_title, R.string.guard_strict_sub,
                { it.strictMode },
                { Graph.settings.setStrictMode(it) }
            )
        )
        guardRows = guards.map { guard ->
            val row = ItemGuardRowBinding.inflate(layoutInflater, b.guardList, true)
            row.guardIcon.setImageResource(guard.iconRes)
            row.guardTitle.setText(guard.titleRes)
            row.guardSub.setText(guard.subRes)
            row.guardSwitch.setOnCheckedChangeListener { button, checked ->
                // Programmatic updates (from the settings flow) must not re-write.
                if (!button.isPressed) return@setOnCheckedChangeListener
                lifecycleScope.launch {
                    guard.setOn(checked)
                    val label = getString(guard.titleRes)
                    Toast.makeText(
                        this@MainActivity,
                        getString(if (checked) R.string.home_toggle_on else R.string.home_toggle_off, label),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            guard to row
        }
    }

    private fun updateGuards(settings: AppSettings) {
        for ((guard, row) in guardRows) {
            val on = guard.isOn(settings)
            if (row.guardSwitch.isChecked != on) row.guardSwitch.isChecked = on
        }
    }

    // --------------------------------------------------------------- animations

    private fun startFloatAnimations() {
        val density = resources.displayMetrics.density
        shieldFloatAnimator = ObjectAnimator.ofFloat(b.shieldTile, "translationY", 0f, -5f * density).apply {
            duration = 5000
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        bannerPulseAnimator = ObjectAnimator.ofFloat(b.lockBannerDot, "alpha", 1f, 0.3f).apply {
            duration = 800
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    // -------------------------------------------------------------------- data

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Graph.db.focusDayDao().observeAll().collect { rows -> updateStats(rows) }
                }
                launch {
                    Graph.settings.flow.collect { settings -> updateGuards(settings) }
                }
            }
        }
    }

    private fun startTickers() {
        // Bengali clock: every 20s, like the demo status bar.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(20_000)
                    b.clockText.text = bengaliClock()
                }
            }
        }
        // Lock ticker: every 500ms, like the demo countdown.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val snap = Graph.lockManager.snapshotNow()
                    updateLockUi(snap)
                    if (snap.active && snap.remainingMs <= 0L) {
                        // Time is up: finish the lock in the engine (the
                        // UnlockWorker does the same when the app is closed).
                        Graph.lockManager.checkpoint()
                    }
                    delay(500)
                }
            }
        }
    }

    // ----------------------------------------------------------------- lock UI

    private fun updateLockUi(snap: LockSnapshot) {
        when {
            snap.active -> {
                b.lockBanner.isVisible = true
                b.lockBannerTime.text = BnDigits.formatHMS(snap.remainingMs)
                b.ringProgress.isVisible = true
                b.ringProgress.progress = (snap.progress * 1000f).toInt()
                b.ringView.progress = snap.progress
                b.ringState.setText(R.string.ring_state_lock)
                b.ringBig.text = BnDigits.formatHMS(snap.remainingMs)
                b.ringSmall.setText(R.string.ring_small_remaining)
            }
            snap.locProtecting -> {
                // Location lock only: protection is on, but there is no time
                // session, so the (time-lock) banner and progress stay hidden.
                b.lockBanner.isVisible = false
                b.ringProgress.isVisible = false
                b.ringView.progress = 0f
                b.ringState.setText(R.string.ring_state_active)
                b.ringBig.setText(R.string.ring_loc_big)
                b.ringSmall.setText(R.string.ring_loc_small)
            }
            else -> {
                b.lockBanner.isVisible = false
                b.ringProgress.isVisible = false
                b.ringView.progress = 0f
                b.ringState.setText(R.string.ring_state_active)
                b.ringBig.setText(R.string.ring_big_ready)
                b.ringSmall.setText(R.string.ring_small_no_lock)
            }
        }
    }

    // -------------------------------------------------------------------- stats

    private fun updateStats(rows: List<FocusDay>) {
        val today = LocalDate.now()
        val summary = StatsCalculator.summarize(rows, today)
        val todayRow = rows.find { it.date == today.toString() }
        val yesterdayRow = rows.find { it.date == today.minusDays(1).toString() }
        val todayBlocks = todayRow?.blocks ?: 0
        val yesterdayBlocks = yesterdayRow?.blocks ?: 0

        // Card 1: blocks today (apps + sites), delta vs yesterday.
        b.stat1Value.text = BnDigits.toBnDigits(todayBlocks.toString())
        b.stat1Delta.text = when {
            todayBlocks == 0 && yesterdayBlocks == 0 -> getString(R.string.home_stat_blocked_empty)
            todayBlocks > yesterdayBlocks -> getString(R.string.home_blocks_delta_more)
            todayBlocks < yesterdayBlocks -> getString(R.string.home_blocks_delta_less)
            else -> getString(R.string.home_blocks_delta_same)
        }

        // Card 2: "saved time" = today's protected (focus) minutes. There is
        // no separate saved-time counter in the engine; protected time is the
        // time the user stayed away from blocked apps instead of using them.
        b.stat2Value.text = formatSavedTime(summary.todayMinutes)
        b.stat2Delta.text = if (summary.streakDays > 0) {
            getString(R.string.home_streak_delta, BnDigits.toBnDigits(summary.streakDays.toString()))
        } else {
            getString(R.string.home_streak_delta_none)
        }

        // Focus line under the ring.
        if (summary.todayMinutes > 0) {
            val duration = formatDurationWords(summary.todayMinutes)
            val streak = BnDigits.toBnDigits(summary.streakDays.toString())
            val text = getString(R.string.home_focus_line, duration, streak)
            val spanned = SpannableStringBuilder(text)
            boldSubstring(spanned, duration)
            boldSubstring(spanned, streak)
            b.focusLine.text = spanned
        } else {
            b.focusLine.setText(R.string.home_focus_line_empty)
        }
    }

    /** Minutes as a short value: "২ ঘ ১০ মি" / "৪৫ মি" / "০ মি". */
    private fun formatSavedTime(minutes: Long): String {
        val m = minutes.coerceAtLeast(0L)
        val hours = m / 60L
        val rest = m % 60L
        return when {
            hours == 0L -> BnDigits.toBnDigits("%d মি".format(rest))
            rest == 0L -> BnDigits.toBnDigits("%d ঘ".format(hours))
            else -> BnDigits.toBnDigits("%d ঘ %d মি".format(hours, rest))
        }
    }

    /** Minutes as words: "৩ ঘণ্টা ২০ মিনিট" / "১ ঘণ্টা" / "৪৫ মিনিট". */
    private fun formatDurationWords(minutes: Long): String {
        val m = minutes.coerceAtLeast(0L)
        val hours = m / 60L
        val rest = m % 60L
        return when {
            hours == 0L -> BnDigits.toBnDigits("%d মিনিট".format(rest))
            rest == 0L -> BnDigits.toBnDigits("%d ঘণ্টা".format(hours))
            else -> BnDigits.toBnDigits("%d ঘণ্টা %d মিনিট".format(hours, rest))
        }
    }

    private fun boldSubstring(text: SpannableStringBuilder, substring: String) {
        val start = text.indexOf(substring)
        if (start >= 0) {
            text.setSpan(StyleSpan(Typeface.BOLD), start, start + substring.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
