package com.pureshield.app.ui.location

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.db.SafeZone
import com.pureshield.app.databinding.ActivityLocationBinding
import com.pureshield.app.databinding.ItemZoneRowBinding
import com.pureshield.app.location.Fix
import com.pureshield.app.location.LocationHelper
import com.pureshield.app.ui.BottomNav
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Location Lock screen (demo Screen 3, verbatim labels).
 *
 * The demo's map was a static SVG; the real map lives in [MapActivity] (real
 * MapLibre map, live location, tap-to-add). This screen shows an entry card
 * for it, the master switch, the radius slider for the selected zone, the
 * saved-place rows with live GPS presence, and the location permission rows.
 *
 * Seed zones (বাড়ি 100m / বাজার 150m) come from [Graph.seedIfFirstRun] and
 * are TEMPLATES: name, radius and mode only, with null coordinates. Tapping
 * one prompts to save the current GPS position as its location — the demo's
 * hard-coded map positions are not real coordinates and are not pretended
 * to be. Live presence uses the current fix when available, otherwise the
 * zone's last-known [SafeZone.isInside] state written by PlaceWatcher.
 */
class LocationActivity : AppCompatActivity() {

    private lateinit var b: ActivityLocationBinding

    private var zones: List<SafeZone> = emptyList()
    private var masterOn: Boolean = true
    private var meFix: Fix? = null
    private var selectedId: Long? = null
    private var updatingUi = false

    private val adapter = ZoneAdapter(
        onRowClick = { zone -> onZoneRowClick(zone) },
        onToggle = { zone, on -> onZoneToggle(zone, on) }
    )

    private val fgPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
        }
    private val bgPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.ACCESS_BACKGROUND_LOCATION] != true) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.loc_perm_bg_title)
                    .setMessage(R.string.loc_perm_bg_off)
                    .setPositiveButton(R.string.loc_perm_settings_button) { _, _ -> openAppSettings() }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityLocationBinding.inflate(layoutInflater)
        setContentView(b.root)
        BottomNav.bind(this, BottomNav.Tab.LOCATION)

        b.rvZones.layoutManager = LinearLayoutManager(this)
        b.rvZones.adapter = adapter

        b.switchMaster.setOnCheckedChangeListener { _, on ->
            if (updatingUi) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                Graph.settings.setLocationLockEnabled(on)
                Toast.makeText(
                    this@LocationActivity,
                    if (on) R.string.loc_master_on_toast else R.string.loc_master_off_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        b.cardMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        b.btnAddPlace.setOnClickListener {
            Toast.makeText(this, R.string.loc_add_hint_toast, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MapActivity::class.java))
        }
        b.btnSaveHere.setOnClickListener { showSaveCurrentPlaceDialog() }
        b.btnFgPerm.setOnClickListener {
            fgPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        b.btnBgPerm.setOnClickListener {
            if (!LocationHelper.hasForegroundPermission(this)) {
                Toast.makeText(this, R.string.loc_perm_fg_off, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            bgPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }

        b.sliderRadius.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updateRadiusLabel(value.toInt())
        }
        b.sliderRadius.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                persistRadius(slider.value.toInt())
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val fixFlow = LocationHelper.updates(this@LocationActivity, 10_000)
                    .map<Fix, Fix?> { it }
                    .onStart { emit(null) }
                combine(
                    Graph.db.safeZoneDao().observeAll(),
                    Graph.settings.flow,
                    fixFlow
                ) { zoneList, settings, fix -> Triple(zoneList, settings, fix) }
                    .collect { (zoneList, settings, fix) ->
                        zones = zoneList
                        masterOn = settings.locationLockEnabled
                        meFix = fix
                        if (selectedId == null || zones.none { it.id == selectedId }) {
                            selectedId = zones.firstOrNull()?.id
                        }
                        render()
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.blurBackground.startMotion()
        refreshPermissions()
    }

    override fun onPause() {
        b.blurBackground.stopMotion()
        super.onPause()
    }

    // ---- rendering ----------------------------------------------------------

    private fun render() {
        updatingUi = true
        try {
            val count = BnDigits.toLocalizedDigits(zones.size.toString())
            b.tvMasterSub.text = if (masterOn) {
                getString(R.string.loc_master_on, count)
            } else {
                getString(R.string.loc_master_off)
            }
            if (b.switchMaster.isChecked != masterOn) b.switchMaster.isChecked = masterOn
            // Master off dims the map card, exactly as the demo does.
            b.cardMap.alpha = if (masterOn) 1f else 0.4f

            val selected = zones.find { it.id == selectedId } ?: zones.firstOrNull()
            if (selected != null) {
                b.tvRadiusTitle.text =
                    getString(R.string.loc_radius_title, selected.name)
                updateRadiusLabel(selected.radiusM)
                if (kotlin.math.abs(b.sliderRadius.value - selected.radiusM) > 0.5f) {
                    b.sliderRadius.value = selected.radiusM.toFloat()
                }
            }
            adapter.submit(zones, masterOn, meFix, selectedId)
            b.rvZones.isVisible = zones.isNotEmpty()
            b.tvNoZones.isVisible = zones.isEmpty()
        } finally {
            updatingUi = false
        }
    }

    private fun updateRadiusLabel(radiusM: Int) {
        b.tvRadiusValue.text =
            getString(R.string.loc_radius_value, BnDigits.toLocalizedDigits(radiusM.toString()))
    }

    private fun persistRadius(radiusM: Int) {
        val zone = zones.find { it.id == selectedId } ?: zones.firstOrNull() ?: return
        if (zone.radiusM == radiusM) return
        lifecycleScope.launch {
            Graph.db.safeZoneDao().upsert(zone.copy(radiusM = radiusM.coerceIn(50, 500)))
            Toast.makeText(
                this@LocationActivity,
                getString(
                    R.string.loc_radius_saved_toast,
                    BnDigits.toLocalizedDigits(radiusM.toString())
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---- zone rows ----------------------------------------------------------

    private fun onZoneRowClick(zone: SafeZone) {
        if (zone.lat == null || zone.lng == null) {
            showSaveLocationPrompt(zone)
        } else {
            selectedId = zone.id
            render()
        }
    }

    private fun onZoneToggle(zone: SafeZone, on: Boolean) {
        lifecycleScope.launch {
            Graph.db.safeZoneDao().upsert(zone.copy(enabled = on))
            Toast.makeText(
                this@LocationActivity,
                getString(
                    if (on) R.string.loc_zone_on_toast else R.string.loc_zone_off_toast,
                    zone.name
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** A template row has no coordinates: offer to save the current GPS as its location. */
    private fun showSaveLocationPrompt(zone: SafeZone) {
        AlertDialog.Builder(this)
            .setTitle(R.string.loc_save_prompt_title)
            .setMessage(getString(R.string.loc_save_prompt_msg, zone.name))
            .setPositiveButton(R.string.loc_save_prompt_ok) { _, _ ->
                saveCurrentFixAsZone(zone.name, zone.radiusM, zone.modeInside, zone.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSaveCurrentPlaceDialog() {
        showZoneEditDialog(
            titleRes = R.string.loc_new_place_title,
            prefillName = "",
            prefillRadius = 100,
            prefillModeInside = true
        ) { name, radius, modeInside ->
            saveCurrentFixAsZone(name, radius, modeInside, null)
        }
    }

    private fun saveCurrentFixAsZone(
        name: String,
        radiusM: Int,
        modeInside: Boolean,
        existingId: Long?
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@LocationActivity, R.string.map_finding_location, Toast.LENGTH_SHORT).show()
            val fix = LocationHelper.currentFix(this@LocationActivity, highAccuracy = true)
            if (fix == null) {
                Toast.makeText(this@LocationActivity, R.string.loc_no_fix, Toast.LENGTH_LONG).show()
                return@launch
            }
            val zone = SafeZone(
                id = existingId ?: 0,
                name = name,
                lat = fix.latitude,
                lng = fix.longitude,
                radiusM = radiusM,
                modeInside = modeInside,
                enabled = true
            )
            val id = Graph.db.safeZoneDao().upsert(zone)
            selectedId = if (existingId != null) existingId else id
            Toast.makeText(
                this@LocationActivity,
                getString(R.string.loc_saved_toast, name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---- permissions ----------------------------------------------------------

    private fun refreshPermissions() {
        val fg = LocationHelper.hasForegroundPermission(this)
        b.tvFgStatus.setText(if (fg) R.string.loc_perm_fg_on else R.string.loc_perm_fg_off)
        b.btnFgPerm.isVisible = !fg
        val bg = LocationHelper.hasBackgroundPermission(this)
        b.tvBgStatus.setText(if (bg) R.string.loc_perm_bg_on else R.string.loc_perm_bg_off)
        b.btnBgPerm.isVisible = !bg
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    // ---- zone edit dialog (built in code: name + radius + mode) --------------

    private fun showZoneEditDialog(
        titleRes: Int,
        prefillName: String,
        prefillRadius: Int,
        prefillModeInside: Boolean,
        onSave: (name: String, radius: Int, modeInside: Boolean) -> Unit
    ) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val etName = EditText(this).apply {
            hint = getString(R.string.loc_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefillName)
        }
        val etRadius = EditText(this).apply {
            hint = getString(R.string.loc_radius_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefillRadius.toString())
        }
        val rgMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbInside = RadioButton(this).apply {
            text = getString(R.string.loc_mode_inside)
            isChecked = prefillModeInside
        }
        val rbAway = RadioButton(this).apply {
            text = getString(R.string.loc_mode_away)
            isChecked = !prefillModeInside
        }
        rgMode.addView(rbInside)
        rgMode.addView(rbAway)
        layout.addView(etName)
        layout.addView(etRadius)
        layout.addView(rgMode)
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(layout)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = etName.text?.toString().orEmpty().trim()
                val radius = etRadius.text?.toString()?.toIntOrNull() ?: 0
                val modeInside = !rbAway.isChecked
                if (name.isEmpty() || radius !in 50..500) {
                    Toast.makeText(this, R.string.loc_invalid, Toast.LENGTH_LONG).show()
                } else {
                    onSave(name, radius, modeInside)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- zone list ----------------------------------------------------------

    private inner class ZoneAdapter(
        private val onRowClick: (SafeZone) -> Unit,
        private val onToggle: (SafeZone, Boolean) -> Unit
    ) : RecyclerView.Adapter<ZoneAdapter.Holder>() {

        private var items: List<SafeZone> = emptyList()
        private var master: Boolean = true
        private var fix: Fix? = null
        private var selected: Long? = null

        fun submit(zones: List<SafeZone>, masterOn: Boolean, fix: Fix?, selectedId: Long?) {
            items = zones
            master = masterOn
            this.fix = fix
            selected = selectedId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemZoneRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val vb: ItemZoneRowBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(zone: SafeZone) {
                vb.tvName.text = zone.name
                vb.tvDetail.text = zoneDetail(zone)
                val locking = master && zone.enabled && lockConditionMet(zone)
                vb.cardTag.isVisible = locking
                vb.switchZone.isVisible = !locking
                vb.switchZone.setOnCheckedChangeListener(null)
                vb.switchZone.isChecked = zone.enabled
                vb.switchZone.setOnCheckedChangeListener { _, on -> onToggle(zone, on) }
                vb.root.setOnClickListener { onRowClick(zone) }
                vb.root.alpha = if (zone.id == selected) 1f else 0.92f
            }

            private fun zoneDetail(zone: SafeZone): String {
                val radius = BnDigits.toLocalizedDigits(zone.radiusM.toString())
                val modeText = getString(
                    if (zone.modeInside) R.string.loc_mode_inside else R.string.loc_mode_away
                )
                val lat = zone.lat
                val lng = zone.lng
                val currentFix = fix
                if (lat == null || lng == null) {
                    return getString(R.string.loc_zone_no_location, radius, modeText)
                }
                if (currentFix == null) {
                    // No live fix yet: fall back to the last-known presence
                    // state written by PlaceWatcher, without a distance.
                    val lockingKnown = when (zone.isInside) {
                        true -> zone.modeInside
                        false -> !zone.modeInside
                        null -> null
                    }
                    return when (lockingKnown) {
                        true -> if (zone.modeInside) {
                            getString(R.string.loc_zone_inside, radius, modeText)
                        } else {
                            getString(R.string.loc_zone_outside, radius, modeText)
                        }
                        else -> getString(R.string.loc_zone_no_distance, radius, modeText)
                    }
                }
                val d = LocationHelper.distanceMeters(
                    currentFix.latitude, currentFix.longitude, lat, lng
                ).toInt()
                val inside = d <= zone.radiusM
                return if (zone.modeInside) {
                    if (inside) getString(R.string.loc_zone_inside, radius, modeText)
                    else getString(
                        R.string.loc_zone_distance, radius, modeText,
                        BnDigits.toLocalizedDigits(d.toString())
                    )
                } else {
                    if (!inside) getString(R.string.loc_zone_outside, radius, modeText)
                    else getString(
                        R.string.loc_zone_distance, radius, modeText,
                        BnDigits.toLocalizedDigits(d.toString())
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_ACCURACY_M = 200f

        /**
         * True when this zone's lock condition is met right now (live GPS
         * presence). Fixes worse than 200 m are ignored, matching PlaceWatcher.
         */
        fun lockConditionMet(zone: SafeZone, fix: Fix?): Boolean {
            if (fix == null || zone.lat == null || zone.lng == null) return false
            if (fix.accuracyM > MAX_ACCURACY_M) return false
            val d = LocationHelper.distanceMeters(
                fix.latitude, fix.longitude, zone.lat, zone.lng
            )
            return if (zone.modeInside) d <= zone.radiusM else d > zone.radiusM
        }
    }

    private fun lockConditionMet(zone: SafeZone): Boolean =
        lockConditionMet(zone, meFix)
}
