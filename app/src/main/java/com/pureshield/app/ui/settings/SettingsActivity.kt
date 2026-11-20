package com.pureshield.app.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.databinding.ActivitySettingsBinding
import com.pureshield.app.databinding.ItemThemeOptionBinding
import com.pureshield.app.service.WorkScheduler
import com.pureshield.app.ui.BottomNav
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.ui.history.RouteHistoryActivity
import com.pureshield.app.ui.pin.PinActivity
import com.pureshield.app.ui.premium.PremiumActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Settings screen (demo Screen 6, verbatim labels).
 *
 * Real behaviour throughout: the 8 theme cards set the theme for real, the
 * partner-PIN row opens the real [PinActivity] set/change flow (the demo's
 * disabled toast is not acceptable), the language row is a real bn/en toggle
 * (locale override + recreate, persisted), the daily-report switch schedules
 * the real daily worker, and the route/history row opens the real
 * [RouteHistoryActivity]. Security switches are persisted in SettingsStore;
 * while Strict mode is on during an active lock they refuse changes with a
 * toast instead of silently ignoring the tap.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    private var strictLocked: Boolean = false
    private var updatingUi = false
    private var currentLanguage: String = "bn"

    private val themeAdapter = ThemeAdapter { theme ->
        lifecycleScope.launch {
            ThemeManager.set(this@SettingsActivity, theme)
            Toast.makeText(
                this@SettingsActivity,
                getString(R.string.settings_theme_changed, theme.displayNameBn),
                Toast.LENGTH_SHORT
            ).show()
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        BottomNav.bind(this, null)

        styleGoldCard()

        b.rvThemes.layoutManager = LinearLayoutManager(this)
        b.rvThemes.adapter = themeAdapter
        themeAdapter.submit(ThemeManager.all(), ThemeManager.current(this))

        b.cardPremium.setOnClickListener {
            startActivity(Intent(this, PremiumActivity::class.java))
        }
        b.btnPinChange.setOnClickListener {
            val intent = Intent(this, PinActivity::class.java).apply {
                putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET)
            }
            startActivity(intent)
        }
        b.rowLanguage.setOnClickListener { showLanguageDialog() }
        b.rowHistory.setOnClickListener {
            startActivity(Intent(this, RouteHistoryActivity::class.java))
        }

        bindSettingSwitch(b.switchStrict, { on -> Graph.settings.setStrictMode(on) }) { on ->
            Toast.makeText(
                this,
                getString(
                    if (on) R.string.block_toggle_on else R.string.block_toggle_off,
                    getString(R.string.settings_strict_title)
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
        bindSettingSwitch(b.switchGuard, { on -> Graph.settings.setSettingsGuard(on) }, null)
        bindSettingSwitch(b.switchBoundary, { on -> Graph.settings.setBoundaryAlert(on) }, null)
        b.switchReport.setOnCheckedChangeListener { _, on ->
            if (updatingUi) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                Graph.settings.setDailyReport(on)
                // The worker itself checks the dailyReport setting before
                // notifying, so persisting the setting turns it off; schedule
                // (KEEP) when turning it on.
                if (on) WorkScheduler.scheduleDailyReport(this@SettingsActivity)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    Graph.settings.flow,
                    Graph.db.lockStateDao().observe()
                ) { settings, lock -> Pair(settings, lock) }
                    .collect { (settings, lock) ->
                        strictLocked = settings.strictMode && lock?.active == true
                        currentLanguage = settings.language
                        updatingUi = true
                        try {
                            b.switchStrict.isChecked = settings.strictMode
                            b.switchGuard.isChecked = settings.settingsGuard
                            b.switchBoundary.isChecked = settings.boundaryAlert
                            b.switchReport.isChecked = settings.dailyReport
                            b.tvLangCurrent.setText(
                                if (currentLanguage == "en") R.string.settings_lang_current_en
                                else R.string.settings_lang_current_bn
                            )
                            val hasPin = Graph.secure.hasPartnerPin()
                            b.tvPinSub.setText(
                                if (hasPin) R.string.settings_pin_sub
                                else R.string.settings_pin_sub_not_set
                            )
                            b.btnPinChange.setText(
                                if (hasPin) R.string.settings_pin_change
                                else R.string.settings_pin_set
                            )
                        } finally {
                            updatingUi = false
                        }
                    }
            }
        }
    }

    // ---- gold premium card (gradient built in code: no drawable files) ------

    private fun styleGoldCard() {
        val gold = attrColor(R.attr.goldColor, Color.rgb(0xDD, 0x94, 0x12))
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(gold, darken(gold, 0.72f))
        )
        drawable.cornerRadius = attrDimen(R.attr.cardCornerRadius, 22f)
        b.cardPremium.background = drawable
    }

    // ---- switches ----------------------------------------------------------

    /**
     * A security switch that refuses changes while Strict mode is on during
     * an active lock — with a toast explaining why, instead of silently
     * ignoring the tap.
     */
    private fun bindSettingSwitch(
        switch: com.google.android.material.switchmaterial.SwitchMaterial,
        save: suspend (Boolean) -> Unit,
        onChanged: ((Boolean) -> Unit)?
    ) {
        switch.setOnCheckedChangeListener { _, on ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (strictLocked) {
                updatingUi = true
                try {
                    switch.isChecked = !on
                } finally {
                    updatingUi = false
                }
                Toast.makeText(this, R.string.settings_strict_locked, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch {
                save(on)
                onChanged?.invoke(on)
            }
        }
    }

    // ---- language ----------------------------------------------------------

    private fun showLanguageDialog() {
        val items = arrayOf("বাংলা", "English")
        val current = if (currentLanguage == "en") 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_lang_dialog_title)
            .setSingleChoiceItems(items, current) { dialog, which ->
                val tag = if (which == 1) "en" else "bn"
                dialog.dismiss()
                if (tag == currentLanguage) return@setSingleChoiceItems
                lifecycleScope.launch {
                    Graph.settings.setLanguage(tag)
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(tag)
                    )
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_lang_changed,
                        Toast.LENGTH_SHORT
                    ).show()
                    recreate()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- theme attribute helpers ----------------------------------------------------------

    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun attrDimen(attr: Int, fallbackDp: Float): Float {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) {
            TypedValue.complexToDimension(tv.data, resources.displayMetrics)
        } else {
            fallbackDp * resources.displayMetrics.density
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val bl = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }

    // ---- theme list ----------------------------------------------------------

    private inner class ThemeAdapter(
        private val onPick: (com.pureshield.app.ui.ShieldTheme) -> Unit
    ) : RecyclerView.Adapter<ThemeAdapter.Holder>() {

        private var themes: List<com.pureshield.app.ui.ShieldTheme> = emptyList()
        private var current: com.pureshield.app.ui.ShieldTheme? = null

        fun submit(
            newThemes: List<com.pureshield.app.ui.ShieldTheme>,
            selected: com.pureshield.app.ui.ShieldTheme
        ) {
            themes = newThemes
            current = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemThemeOptionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(themes[position])
        }

        override fun getItemCount(): Int = themes.size

        inner class Holder(private val vb: ItemThemeOptionBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(theme: com.pureshield.app.ui.ShieldTheme) {
                vb.tvThemeName.text = theme.displayNameBn
                vb.tvThemeDesc.text = theme.descriptionBn
                vb.tvCheck.isVisible = theme == current
                // The three overlapping dots preview the theme: its own colours
                // and its own corner radius (resolved from the theme's style).
                val radius = themeCornerRadius(theme)
                val dots = listOf(vb.dot1, vb.dot2, vb.dot3)
                val colors = theme.dotColors
                for (i in dots.indices) {
                    val color = if (i < colors.size) colors[i] else Color.GRAY
                    dots[i].setCardBackgroundColor(color)
                    dots[i].radius = radius
                }
                vb.root.setOnClickListener { onPick(theme) }
            }

            /**
             * The theme's own card corner radius, read from its style — so the
             * dot shape really previews that theme (round for Relax, near-square
             * for Eye Comfort, sharp for Original, …). Capped at half the dot.
             */
            private fun themeCornerRadius(theme: com.pureshield.app.ui.ShieldTheme): Float {
                val halfDot = 11f * resources.displayMetrics.density
                return try {
                    val a = obtainStyledAttributes(
                        theme.styleRes, intArrayOf(R.attr.cardCornerRadius)
                    )
                    val px = a.getDimensionPixelSize(0, -1)
                    a.recycle()
                    if (px < 0) halfDot else px.toFloat().coerceAtMost(halfDot)
                } catch (e: Exception) {
                    halfDot
                }
            }
        }
    }
}
