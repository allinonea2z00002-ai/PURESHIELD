package com.pureshield.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
import com.pureshield.app.databinding.ItemPermissionRowBinding
import com.pureshield.app.databinding.ItemQuickActionBinding
import com.pureshield.app.databinding.ItemQuickActionWideBinding
import com.pureshield.app.domain.LockSnapshot
import com.pureshield.app.ui.blocking.BlockingActivity
import com.pureshield.app.ui.location.LocationActivity
import com.pureshield.app.ui.settings.SettingsActivity
import com.pureshield.app.ui.stats.StatsActivity
import com.pureshield.app.ui.timelock.TimeLockActivity
import com.pureshield.app.util.BnDigits
import com.pureshield.app.util.PermissionKind
import com.pureshield.app.util.PermissionPrompter
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
    private var pulseAnimator: ValueAnimator? = null
    private var bannerPulseAnimator: ObjectAnimator? = null
    private var guardRows: List<Pair<GuardRow, ItemGuardRowBinding>> = emptyList()

    /** True while any protection is active (time session OR location lock). Guard
     *  toggles refuse changes while this is true — the lock must end first. */
    private var lockActive = false

    /**
     * In-app system dialogs for the two runtime permissions, so tapping Allow
     * on the Home card grants them without leaving the app. On grant the card
     * refreshes (and the prompt notification is cancelled by the sync); on a
     * permanent denial ("don't ask again") Settings is the only way left, so
     * the deep-link opens instead.
     */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissions()
        if (!granted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            startActivity(
                PermissionKind.LOCATION_FINE.intent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissions()
        if (!granted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            startActivity(
                PermissionKind.NOTIFICATIONS.intent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private data class GuardRow(
        @DrawableRes val iconRes: Int,
        /** Theme colour attr for this guard's icon + tile (mint / blue / gold). */
        @AttrRes val tintAttr: Int,
        @StringRes val titleRes: Int,
        @StringRes val subRes: Int,
        val isOn: (AppSettings) -> Boolean,
        val setOn: suspend (Boolean) -> Unit
    )

    private data class QuickAction(
        @DrawableRes val iconRes: Int,
        /** Theme colour attr for this action's icon + tile (mint / blue / red / gold). */
        @AttrRes val tintAttr: Int,
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

        setupHeader(theme)
        setupSectionTitles()
        setupQuickActions(theme.quickActionColumns)
        buildGuards()
        startFloatAnimations()
        observeData()
        startTickers()
        // Cold start: permissions that are STILL missing get their
        // notifications again automatically (see resetStillMissing) — a
        // once-dismissed prompt must not stay silent forever.
        lifecycleScope.launch {
            PermissionPrompter.resetStillMissing(this@MainActivity)
            PermissionPrompter.checkAndPrompt(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A notification tap arrives here when this activity is already on
        // top: keep the new intent so onResume sees its request extra.
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        b.blurBackground.startMotion()
        // The user may just have granted a permission in Settings: re-check,
        // clear the prompts that are granted now, and sync the Home card.
        refreshPermissions()
        // A permission notification was tapped: fire the in-app system dialog
        // once, then drop the extra so coming back from the dialog does not
        // re-fire it.
        when (intent.getStringExtra(PermissionPrompter.EXTRA_REQUEST_PERMISSION)) {
            PermissionKind.LOCATION_FINE.requestKey -> {
                intent.removeExtra(PermissionPrompter.EXTRA_REQUEST_PERMISSION)
                requestLocationPermission()
            }
            PermissionKind.NOTIFICATIONS.requestKey -> {
                intent.removeExtra(PermissionPrompter.EXTRA_REQUEST_PERMISSION)
                requestNotificationPermission()
            }
        }
    }

    override fun onPause() {
        b.blurBackground.stopMotion()
        super.onPause()
    }

    override fun onDestroy() {
        shieldFloatAnimator?.cancel()
        pulseAnimator?.cancel()
        bannerPulseAnimator?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ header

    private fun setupHeader(theme: ShieldTheme) {
        b.clockText.text = localizedClock()

        // Demo avatar: white "P" on the accent gradient, except Zen, whose
        // avatar is transparent with a mint-deep letter.
        if (theme == ShieldTheme.ZEN) {
            b.avatarText.setTextColor(attrColor(R.attr.mintDeepColor))
        }

        b.themeButton.setOnClickListener {
            val next = ThemeManager.cycle(this)
            val name = if (BnDigits.useBengali()) next.displayNameBn else next.displayNameEn
            Toast.makeText(this, getString(R.string.home_theme_toast, name), Toast.LENGTH_SHORT).show()
            recreate()
        }
        b.bellButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.guardSeeAll.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * Demo section titles: only the Original theme draws the 3px mint left
     * border with .05em letter spacing (`.sec-title h3`).
     */
    private fun setupSectionTitles() {
        if (!attrBoolean(R.attr.titleLeftBorder, false)) return
        val density = resources.displayMetrics.density
        for (title in listOf(b.quickTitle, b.guardTitle)) {
            title.background = ContextCompat.getDrawable(this, R.drawable.bg_title_left_border)
            title.setPadding(
                (10 * density).toInt(), title.paddingTop, title.paddingEnd, title.paddingBottom
            )
            title.letterSpacing = 0.05f
        }
    }

    /** Live clock, 12-hour (e.g. "৯:৪১" in Bengali mode, "9:41" in English). */
    private fun localizedClock(): String {
        val now = LocalTime.now()
        val hour12 = when (val h = now.hour % 12) {
            0 -> 12
            else -> h
        }
        return BnDigits.toLocalizedDigits("%d:%02d".format(hour12, now.minute))
    }

    // ------------------------------------------------------------ quick actions

    private fun setupQuickActions(columns: Int) {
        // Demo icon colours (same in every theme): Time Lock clock mint,
        // Location pin blue, Block list X-in-rounded-square red, Report
        // bar-chart gold.
        val items = listOf(
            QuickAction(R.drawable.ic_timer, R.attr.mintColor, R.string.home_quick_time_lock) {
                startActivity(Intent(this, TimeLockActivity::class.java))
            },
            QuickAction(R.drawable.ic_pin, R.attr.blueColor, R.string.home_quick_location) {
                startActivity(Intent(this, LocationActivity::class.java))
            },
            QuickAction(R.drawable.ic_block_x, R.attr.redColor, R.string.home_quick_block_list) {
                startActivity(Intent(this, BlockingActivity::class.java))
            },
            QuickAction(R.drawable.ic_assessment, R.attr.goldColor, R.string.home_quick_report) {
                startActivity(Intent(this, StatsActivity::class.java))
            }
        )
        b.quickGrid.layoutManager = GridLayoutManager(this, columns)
        b.quickGrid.adapter = QuickAdapter(items, wide = columns == 2)
    }

    /**
     * Demo icon tile: the action's colour at ~13% alpha, corner radius from
     * the theme ([R.attr.iconTileRadius]); the icon itself is tinted the
     * full colour.
     */
    private fun setIconTile(icon: AppCompatImageView, @ColorInt color: Int) {
        val tile = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ColorUtils.setAlphaComponent(color, 33))
            cornerRadius = attrDimension(R.attr.iconTileRadius)
        }
        icon.background = tile
        icon.imageTintList = ColorStateList.valueOf(color)
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
            setIconTile(holder.icon, this@MainActivity.attrColor(item.tintAttr))
            holder.label.setText(item.labelRes)
            holder.root.setOnClickListener { item.onClick() }
        }

        override fun getItemCount(): Int = items.size
    }

    // ------------------------------------------------------------------- guards

    private fun buildGuards() {
        // Demo guard icons (same in every theme): Power menu block = shield
        // outline mint, Uninstall protection = bin outline blue, Strict
        // mode = sun gold.
        val guards = listOf(
            GuardRow(
                R.drawable.ic_guard_shield, R.attr.mintColor,
                R.string.guard_power_title, R.string.guard_power_sub,
                { it.powerMenuBlock },
                { Graph.settings.setPowerMenuBlock(it) }
            ),
            GuardRow(
                R.drawable.ic_guard_bin, R.attr.blueColor,
                R.string.guard_uninstall_title, R.string.guard_uninstall_sub,
                { it.uninstallProtection },
                { Graph.settings.setUninstallProtection(it) }
            ),
            GuardRow(
                R.drawable.ic_guard_sun, R.attr.goldColor,
                R.string.guard_strict_title, R.string.guard_strict_sub,
                { it.strictMode },
                { Graph.settings.setStrictMode(it) }
            )
        )
        guardRows = guards.map { guard ->
            val row = ItemGuardRowBinding.inflate(layoutInflater, b.guardList, true)
            row.guardIcon.setImageResource(guard.iconRes)
            setIconTile(row.guardIcon, attrColor(guard.tintAttr))
            row.guardTitle.setText(guard.titleRes)
            row.guardSub.setText(guard.subRes)
            row.guardSwitch.setOnCheckedChangeListener { button, checked ->
                // Programmatic updates (from the settings flow) must not re-write.
                if (!button.isPressed) return@setOnCheckedChangeListener
                // While a lock is running the guard switches cannot be flipped:
                // revert the tap and point at the partner-PIN emergency unlock.
                if (lockActive) {
                    button.isChecked = !checked
                    Toast.makeText(
                        this@MainActivity,
                        R.string.locked_change_blocked,
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                }
                lifecycleScope.launch {
                    guard.setOn(checked)
                    // Protection just turned on: prompt for any permission the
                    // lock still needs (one notification per permission).
                    if (checked) PermissionPrompter.checkAndPrompt(this@MainActivity)
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
        // Dividers separate rows; the last row has none (demo .guards).
        guardRows.lastOrNull()?.second?.guardDivider?.isVisible = false
    }

    private fun updateGuards(settings: AppSettings) {
        for ((guard, row) in guardRows) {
            val on = guard.isOn(settings)
            if (row.guardSwitch.isChecked != on) row.guardSwitch.isChecked = on
        }
    }

    // ------------------------------------------------------- permission prompts

    /** Re-check every permission the lock needs, sync notifications + the Home card. */
    private fun refreshPermissions() {
        lifecycleScope.launch {
            val missing = PermissionPrompter.missing(this@MainActivity)
            PermissionPrompter.sync(this@MainActivity, missing)
            updatePermCard(missing)
        }
    }

    /** The Home card shows exactly the same missing list as the notifications. */
    private fun updatePermCard(missing: List<PermissionKind>) {
        b.permCard.isVisible = missing.isNotEmpty()
        if (missing.isEmpty()) return
        b.permList.removeAllViews()
        for (kind in missing) {
            val row = ItemPermissionRowBinding.inflate(layoutInflater, b.permList, true)
            row.permRowTitle.setText(kind.titleRes)
            row.permRowSub.setText(kind.textRes)
            row.permRowButton.setOnClickListener {
                // Runtime permissions are granted with the system dialog
                // inside the app; the special kinds open their settings
                // screen (Android lets no app grant those itself).
                when (kind) {
                    PermissionKind.LOCATION_FINE -> requestLocationPermission()
                    PermissionKind.NOTIFICATIONS -> requestNotificationPermission()
                    else -> startActivity(
                        kind.intent(this@MainActivity)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    /** Fine location is a runtime permission: ask with the system dialog. */
    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Notifications are a runtime permission on Android 13+; below that
     * there is no system dialog, so open the app notification settings
     * directly.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                PermissionKind.NOTIFICATIONS.intent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // --------------------------------------------------------------- animations

    private fun startFloatAnimations() {
        val theme = ThemeManager.current(this)
        if (theme == ShieldTheme.ORIGINAL) {
            // Demo Original badge: expanding pulse ring (2.6s), no float.
            // Demo @keyframes pulse: the ring spreads 0 -> 16px and fades
            // .35 -> 0 over the first 70% of the cycle, then rests.
            b.pulseRing.isVisible = true
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2600
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    val t = (f / 0.7f).coerceIn(0f, 1f)
                    val scale = 1f + 0.22f * t
                    b.pulseRing.scaleX = scale
                    b.pulseRing.scaleY = scale
                    b.pulseRing.alpha = 0.35f * (1f - t)
                }
                start()
            }
        } else if (theme != ShieldTheme.ZEN) {
            // Demo shieldFloat: gentle -5px bob, 5s (Zen has no badge animation).
            val density = resources.displayMetrics.density
            shieldFloatAnimator = ObjectAnimator.ofFloat(b.shieldTile, "translationY", 0f, -5f * density).apply {
                duration = 5000
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
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
                    b.clockText.text = localizedClock()
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
        lockActive = snap.protecting
        when {
            snap.active -> {
                b.lockBanner.isVisible = true
                b.lockBannerTime.text = BnDigits.formatHMSLocalized(snap.remainingMs)
                b.ringProgress.isVisible = true
                // The ring shows REMAINING time (full at start, empty at the end).
                b.ringProgress.progress = ((1f - snap.progress) * 1000f).toInt()
                b.ringView.progress = snap.progress
                b.ringState.setText(R.string.ring_state_lock)
                b.ringBig.text = BnDigits.formatHMSLocalized(snap.remainingMs)
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
        b.stat1Value.text = BnDigits.toLocalizedDigits(todayBlocks.toString())
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
            getString(R.string.home_streak_delta, BnDigits.toLocalizedDigits(summary.streakDays.toString()))
        } else {
            getString(R.string.home_streak_delta_none)
        }

        // Focus line under the ring.
        if (summary.todayMinutes > 0) {
            val duration = formatDurationWords(summary.todayMinutes)
            val streak = BnDigits.toLocalizedDigits(summary.streakDays.toString())
            val text = getString(R.string.home_focus_line, duration, streak)
            val spanned = SpannableStringBuilder(text)
            boldSubstring(spanned, duration)
            boldSubstring(spanned, streak)
            b.focusLine.text = spanned
        } else {
            b.focusLine.setText(R.string.home_focus_line_empty)
        }
    }

    /** Minutes as a short value: "২ ঘ ১০ মি" / "৪৫ মি" / "০ মি" in Bengali mode, "2 hr 10 min" / "45 min" / "0 min" in English. */
    private fun formatSavedTime(minutes: Long): String =
        BnDigits.formatMinutesShort(minutes)

    /** Minutes as words: "৩ ঘণ্টা ২০ মিনিট" / "১ ঘণ্টা" / "৪৫ মিনিট" in Bengali mode, "3 hours 20 minutes" / "1 hour" / "45 minutes" in English. */
    private fun formatDurationWords(minutes: Long): String =
        BnDigits.formatMinutesLocalized(minutes)

    private fun boldSubstring(text: SpannableStringBuilder, substring: String) {
        val start = text.indexOf(substring)
        if (start >= 0) {
            text.setSpan(StyleSpan(Typeface.BOLD), start, start + substring.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
