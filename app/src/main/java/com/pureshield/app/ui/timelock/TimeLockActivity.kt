package com.pureshield.app.ui.timelock

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.db.LockState
import com.pureshield.app.databinding.ActivityTimeLockBinding
import com.pureshield.app.databinding.ItemDayPillBinding
import com.pureshield.app.databinding.ItemLockRowBinding
import com.pureshield.app.domain.LockEvaluator
import com.pureshield.app.domain.LockManager
import com.pureshield.app.domain.LockSnapshot
import com.pureshield.app.ui.BottomNav
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.ui.pin.PinActivity
import com.pureshield.app.ui.pin.PinEntrySheet
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Repeat-day + combine preferences. These live in this screen's small private DataStore
 * ("pureshield_timelock") for the UI, and are MIRRORED into SettingsStore (the engine's
 * DataStore) so LockManager and the repeat worker can read them: key
 * "time_lock_repeat_days" (bitmask, Sat=bit0 ... Fri=bit6), "combine_with_location"
 * (boolean, default true) and "last_minutes" (the duration to re-start with).
 * The daily repeat itself runs from GuardWorker via RepeatLock (see WorkScheduler).
 */
private val Context.timeLockDataStore by preferencesDataStore(name = "pureshield_timelock")
private val KEY_REPEAT_DAYS = intPreferencesKey("time_lock_repeat_days")
private val KEY_COMBINE = booleanPreferencesKey("combine_with_location")
private val KEY_LAST_MINUTES = intPreferencesKey("last_minutes")

/** Default repeat days: Sun + Mon + Wed (demo pre-selection). Sat=bit0 ... Fri=bit6. */
private const val DEFAULT_REPEAT_MASK = (1 shl 1) or (1 shl 2) or (1 shl 4)

/**
 * Time Lock (demo Screen 2): preset chips 1/3/10/30/60 (10 selected), custom
 * minutes (min 3, max 1440), repeat-day pills, combine-with-location switch,
 * running countdown card and the emergency PIN unlock.
 *
 * Validation note: the 1-minute chip is a sanctioned engine preset
 * (LockEvaluator.TIME_PRESETS_MINUTES), so chips always start. The "min 3"
 * rule governs custom input, where LockEvaluator.MIN_CUSTOM_MINUTES = 3.
 */
class TimeLockActivity : AppCompatActivity() {

    private lateinit var b: ActivityTimeLockBinding

    private var selectedMinutes: Long? = 10L
    private var repeatMask: Int = DEFAULT_REPEAT_MASK
    private var wasActive = false
    private var lastSnap: LockSnapshot = LockSnapshot.INACTIVE
    private var lastState: LockState? = null
    private var unlockedByPin = false
    private var pendingPinSheet = false
    private var runningPulseAnimator: ObjectAnimator? = null

    private lateinit var chipViews: List<Pair<TextView, Long>>
    private lateinit var pillBindings: List<ItemDayPillBinding>
    private lateinit var appsRow: ItemLockRowBinding

    private val pinSetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* PIN-set result is picked up in onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityTimeLockBinding.inflate(layoutInflater)
        setContentView(b.root)

        BottomNav.bind(this, BottomNav.Tab.TIME_LOCK)

        setupChips()
        setupDayPills()
        setupCombine()
        setupStart()
        setupEmergency()
        buildLockRows()
        startPulse()

        supportFragmentManager.setFragmentResultListener(
            PinEntrySheet.REQUEST_KEY, this
        ) { _, bundle ->
            if (bundle.getBoolean(PinEntrySheet.RESULT_UNLOCKED, false)) {
                unlockedByPin = true
                Toast.makeText(this, R.string.pin_unlocked, Toast.LENGTH_SHORT).show()
            }
        }

        observeData()
        startTicker()
    }

    override fun onResume() {
        super.onResume()
        if (pendingPinSheet && Graph.secure.hasPartnerPin()) {
            pendingPinSheet = false
            showPinSheet()
        }
    }

    override fun onDestroy() {
        runningPulseAnimator?.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------- setup

    private fun setupChips() {
        chipViews = listOf(
            b.chip1 to 1L,
            b.chip3 to 3L,
            b.chip10 to 10L,
            b.chip30 to 30L,
            b.chip60 to 60L
        )
        for ((view, minutes) in chipViews) {
            view.setOnClickListener {
                b.customEdit.setText("")
                selectedMinutes = minutes
                updateChips()
            }
        }
        // Typing a custom value deselects the chips.
        b.customEdit.doAfterTextChanged { text ->
            if (!text.isNullOrEmpty()) {
                selectedMinutes = null
                updateChips()
            }
        }
        updateChips()
    }

    private fun updateChips() {
        for ((view, minutes) in chipViews) {
            view.isSelected = selectedMinutes == minutes
        }
    }

    private fun setupDayPills() {
        val labels = listOf(
            R.string.timelock_day_sat,
            R.string.timelock_day_sun,
            R.string.timelock_day_mon,
            R.string.timelock_day_tue,
            R.string.timelock_day_wed,
            R.string.timelock_day_thu,
            R.string.timelock_day_fri
        )
        pillBindings = labels.mapIndexed { index, labelRes ->
            val pill = ItemDayPillBinding.inflate(layoutInflater, b.dayRow, true)
            pill.root.setText(labelRes)
            pill.root.setOnClickListener { toggleDay(index) }
            pill
        }
        updateDayPills()
    }

    private fun updateDayPills() {
        pillBindings.forEachIndexed { index, pill ->
            pill.root.isSelected = (repeatMask and (1 shl index)) != 0
        }
    }

    private fun toggleDay(index: Int) {
        val newMask = repeatMask xor (1 shl index)
        lifecycleScope.launch {
            timeLockDataStore.edit { it[KEY_REPEAT_DAYS] = newMask }
            Graph.settings.setRepeatDaysMask(newMask)
        }
    }

    private fun setupCombine() {
        b.combineSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                timeLockDataStore.edit { it[KEY_COMBINE] = checked }
                Graph.settings.setCombineWithLocation(checked)
            }
        }
    }

    private fun setupStart() {
        b.startButton.setOnClickListener {
            val customText = b.customEdit.text?.toString()?.trim().orEmpty()
            val minutes: Long? = if (customText.isNotEmpty()) {
                customText.toLongOrNull()
            } else {
                selectedMinutes
            }
            if (minutes == null) {
                Toast.makeText(this, R.string.timelock_min_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (customText.isNotEmpty()) {
                if (minutes < LockEvaluator.MIN_CUSTOM_MINUTES) {
                    Toast.makeText(this, R.string.timelock_min_toast, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (minutes > LockEvaluator.MAX_CUSTOM_MINUTES) {
                    Toast.makeText(this, R.string.timelock_max_toast, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            lifecycleScope.launch {
                when (Graph.lockManager.start(minutes)) {
                    LockManager.StartResult.Started -> {
                        timeLockDataStore.edit { it[KEY_LAST_MINUTES] = minutes.toInt() }
                        Graph.settings.setLastLockMinutes(minutes.toInt())
                        val now = java.time.LocalTime.now()
                        Graph.settings.setLastLockStart(now.hour, now.minute)
                        Toast.makeText(
                            this@TimeLockActivity,
                            getString(
                                R.string.timelock_started,
                                BnDigits.toBnDigits(minutes.toString())
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    LockManager.StartResult.AlreadyActive -> {
                        // The running card appears via the ticker.
                    }
                    LockManager.StartResult.InvalidDuration -> {
                        Toast.makeText(
                            this@TimeLockActivity,
                            R.string.timelock_min_toast,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun setupEmergency() {
        b.emergencyButton.setOnClickListener {
            if (!Graph.secure.hasPartnerPin()) {
                // No PIN yet: set one first, then offer the sheet on return.
                pendingPinSheet = true
                val intent = Intent(this, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET)
                pinSetLauncher.launch(intent)
            } else {
                showPinSheet()
            }
        }
    }

    private fun showPinSheet() {
        PinEntrySheet.newInstance().show(supportFragmentManager, PinEntrySheet.TAG)
    }

    private fun buildLockRows() {
        appsRow = ItemLockRowBinding.inflate(layoutInflater, b.lockList, true)
        appsRow.lockTitle.setText(R.string.timelock_row_apps)
        appsRow.lockSub.setText(R.string.timelock_row_apps_none)

        val webRow = ItemLockRowBinding.inflate(layoutInflater, b.lockList, true)
        webRow.lockTitle.setText(R.string.timelock_row_web)
        webRow.lockSub.setText(R.string.timelock_row_web_sub)

        val powerRow = ItemLockRowBinding.inflate(layoutInflater, b.lockList, true)
        powerRow.lockTitle.setText(R.string.timelock_row_power)
        powerRow.lockSub.setText(R.string.timelock_row_power_sub)

        val installRow = ItemLockRowBinding.inflate(layoutInflater, b.lockList, true)
        installRow.lockTitle.setText(R.string.timelock_row_install)
        installRow.lockSub.setText(R.string.timelock_row_install_sub)
    }

    private fun startPulse() {
        runningPulseAnimator = ObjectAnimator.ofFloat(b.runningDot, "alpha", 1f, 0.3f).apply {
            duration = 800
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    // --------------------------------------------------------------------- data

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    timeLockDataStore.data.collect { prefs ->
                        repeatMask = prefs[KEY_REPEAT_DAYS] ?: DEFAULT_REPEAT_MASK
                        updateDayPills()
                        val combine = prefs[KEY_COMBINE] ?: true
                        if (b.combineSwitch.isChecked != combine) {
                            b.combineSwitch.isChecked = combine
                        }
                    }
                }
                launch {
                    Graph.lockManager.observe().collect { state ->
                        lastState = state
                    }
                }
                launch {
                    Graph.db.blockedAppDao().observeAll().collect { apps ->
                        val count = apps.count { it.blocked }
                        appsRow.lockSub.text = if (count > 0) {
                            getString(
                                R.string.timelock_row_apps_sub,
                                BnDigits.toBnDigits(count.toString())
                            )
                        } else {
                            getString(R.string.timelock_row_apps_none)
                        }
                    }
                }
            }
        }
    }

    private fun startTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val snap = Graph.lockManager.snapshotNow()
                    if (wasActive && !snap.active) {
                        val byPin = unlockedByPin
                        unlockedByPin = false
                        // Only the time ending gets the "time's up" toast: a
                        // distance unlock or a PIN unlock is reported elsewhere.
                        if (!byPin && lastSnap.timeSatisfied) {
                            Toast.makeText(
                                this@TimeLockActivity,
                                R.string.timelock_finished,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    wasActive = snap.active
                    lastSnap = snap
                    updateRunning(snap)
                    if (snap.active && snap.remainingMs <= 0L) {
                        Graph.lockManager.checkpoint()
                    }
                    delay(500)
                }
            }
        }
    }

    // ------------------------------------------------------------------ running

    private fun updateRunning(snap: LockSnapshot) {
        val running = snap.active
        b.setupCard.isVisible = !running
        b.startButton.isVisible = !running
        b.runningCard.isVisible = running
        if (!running) return

        b.countdownText.text = BnDigits.formatHMS(snap.remainingMs)
        b.endsText.text = getString(R.string.timelock_ends, endTimeBn())
        b.lockProgress.progress = (snap.progress * 1000f).toInt()
    }

    /** End time of the running lock as a Bengali 12-hour "H:MM". */
    private fun endTimeBn(): String {
        val state = lastState ?: return ""
        if (state.durationMs <= 0L) return ""
        val end = Instant.ofEpochMilli(state.startEpoch + state.durationMs)
            .atZone(ZoneId.systemDefault()).toLocalTime()
        val hour12 = when (val h = end.hour % 12) {
            0 -> 12
            else -> h
        }
        return BnDigits.toBnDigits("%d:%02d".format(hour12, end.minute))
    }
}
