package com.pureshield.app.ui.location

import android.Manifest
import android.os.Bundle
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.db.SafeZone
import com.pureshield.app.databinding.ActivityMapBinding
import com.pureshield.app.location.Fix
import com.pureshield.app.location.LocationHelper
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real map of safe zones (Location screen > "View map").
 *
 * WebView + MapLibre GL JS + CARTO Positron, no new dependency (MapLibre loads
 * from the CDN in the WebView; the page itself is the bundled asset
 * [maplibre_map.html]). Zones show as pins with radius circles — green while
 * the zone is locking right now, blue otherwise — with a live
 * current-location dot (white, as in the demo legend), tap-on-map to add a
 * zone, a radius slider, and a map / satellite / OSM style switch. Map tiles
 * need internet; locking itself stays GPS/offline.
 *
 * Zone colours match the demo: green dashed circle for the locking zone,
 * blue for other zones, white "you are here" dot. Template zones without
 * coordinates are not drawn on the map.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var b: ActivityMapBinding
    private lateinit var web: WebView

    private var pageReady = false
    private val pendingJs = ArrayList<String>()

    private var zones: List<SafeZone> = emptyList()
    private var masterOn = true
    private var meFix: Fix? = null
    private var selectedId: Long? = null
    private var pendingLat: Double? = null
    private var pendingLng: Double? = null
    private var editRadius = 200
    private var satOn = false

    private val radiusChips = ArrayList<MaterialButton>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    // ---- JS bridge: called from the MapLibre map ----------------------

    inner class MapBridge {
        @JavascriptInterface
        fun onMapTap(lat: Double, lng: Double) {
            runOnUiThread {
                pendingLat = lat
                pendingLng = lng
                selectedId = null
                pushPendingJs()
                render()
            }
        }

        @JavascriptInterface
        fun onPlaceTap(id: Long) {
            runOnUiThread { selectZone(id) }
        }
    }

    // ---- lifecycle ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityMapBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvBack.setOnClickListener { finish() }
        b.btnMyLocation.setOnClickListener { recenterOnMe() }
        b.btnSatellite.setOnClickListener { toggleSat() }
        b.btnAddZone.setOnClickListener {
            val la = pendingLat
            val lo = pendingLng
            if (la != null && lo != null) openAddZoneDialog(la, lo)
        }
        b.btnSaveZone.setOnClickListener { saveSelected() }
        b.btnDeleteZone.setOnClickListener { deleteSelected() }

        for ((index, r) in RADII.withIndex()) {
            val chip = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            chip.text = "$r m"
            chip.isCheckable = true
            chip.setOnClickListener { setEditRadius(r) }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index < RADII.size - 1) params.marginEnd = dp(8)
            radiusChips.add(chip)
            b.rowRadiusChips.addView(chip, params)
        }
        b.seekRadius.max = (MAX_RADIUS - MIN_RADIUS) / RADIUS_STEP
        b.seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setEditRadius(MIN_RADIUS + p * RADIUS_STEP)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        web = b.webMap
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(MapBridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                for (js in pendingJs) {
                    try {
                        web.evaluateJavascript(js, null)
                    } catch (_: Exception) {
                    }
                }
                pendingJs.clear()
                pushDataJs()
                pushMeJs()
            }
        }
        web.loadDataWithBaseURL("https://unpkg.com", mapHtml(), "text/html", "utf-8", null)

        if (!LocationHelper.hasForegroundPermission(this)) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Graph.db.safeZoneDao().observeAll().collect {
                        zones = it
                        render()
                        pushDataJs()
                    }
                }
                launch {
                    Graph.settings.flow.collect {
                        masterOn = it.locationLockEnabled
                        render()
                        pushDataJs()
                    }
                }
                launch {
                    LocationHelper.updates(this@MapActivity, 10_000).collect { fix ->
                        meFix = fix
                        render()
                        pushMeJs()
                        pushDataJs()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            web.onResume()
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        try {
            web.onPause()
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---- location + panel -------------------------------------------------

    /** Switch the MapLibre style between Positron map and Esri satellite. */
    private fun toggleSat() {
        satOn = !satOn
        b.btnSatellite.setText(if (satOn) R.string.map_map else R.string.map_satellite)
        eval("setSat($satOn);")
    }

    private fun recenterOnMe() {
        lifecycleScope.launch {
            Toast.makeText(this@MapActivity, R.string.map_finding_location, Toast.LENGTH_SHORT).show()
            val fix = LocationHelper.currentFix(this@MapActivity, highAccuracy = true)
            if (fix == null) {
                Toast.makeText(this@MapActivity, R.string.map_location_failed, Toast.LENGTH_LONG).show()
            } else {
                meFix = fix
                render()
                pushMeJs()
                pushDataJs()
                eval("recenter(${fix.latitude},${fix.longitude});")
            }
        }
    }

    /** Green circle: this zone is locking right now (master on, zone enabled, condition met). */
    private fun zoneActive(zone: SafeZone): Boolean {
        if (!masterOn || !zone.enabled) return false
        return LocationActivity.lockConditionMet(zone, meFix)
    }

    private fun distanceTo(zone: SafeZone): Float? {
        val fix = meFix ?: return null
        val lat = zone.lat ?: return null
        val lng = zone.lng ?: return null
        return LocationHelper.distanceMeters(fix.latitude, fix.longitude, lat, lng)
    }

    private fun render() {
        if (!::b.isInitialized) return
        val locking = masterOn && zones.any { zoneActive(it) }
        if (locking) {
            b.tvMapBanner.setText(R.string.map_lock_active)
            b.tvMapBanner.setTextColor(MaterialColors.getColor(this, R.attr.mintDeepColor, 0))
        } else {
            b.tvMapBanner.setText(R.string.map_lock_open)
            b.tvMapBanner.setTextColor(MaterialColors.getColor(this, R.attr.subColor, 0))
        }

        val sel = zones.find { it.id == selectedId }
        if (sel != null) {
            b.layoutEditZone.isVisible = true
            b.btnAddZone.isVisible = false
            b.tvMapInfo.text = sel.name
            val d = distanceTo(sel)
            b.tvMapDistance.text = if (d == null) {
                getString(R.string.map_location_unavailable)
            } else {
                val where = if (zoneActive(sel)) getString(R.string.map_inside_locking)
                else getString(R.string.map_outside_open)
                getString(
                    R.string.map_m_away, BnDigits.toLocalizedDigits(d.toInt().toString())
                ) + " · " + where
            }
            return
        }
        b.layoutEditZone.isVisible = false
        val pla = pendingLat
        val plo = pendingLng
        if (pla != null && plo != null) {
            b.btnAddZone.isVisible = true
            b.tvMapInfo.setText(R.string.map_new_zone)
            val coords = String.format(java.util.Locale.US, "%.5f, %.5f", pla, plo)
            val fix = meFix
            b.tvMapDistance.text = if (fix == null) {
                coords + " · " + getString(R.string.map_location_unavailable)
            } else {
                val d = LocationHelper.distanceMeters(fix.latitude, fix.longitude, pla, plo)
                getString(
                    R.string.map_m_away, BnDigits.toLocalizedDigits(d.toInt().toString())
                ) + " · " + coords
            }
        } else {
            b.btnAddZone.isVisible = false
            b.tvMapInfo.setText(R.string.map_tap_hint)
            val placed = zones.count { it.lat != null && it.lng != null }
            b.tvMapDistance.text = if (placed == 0) {
                getString(R.string.map_no_zones)
            } else {
                getString(
                    R.string.map_zones_saved, BnDigits.toLocalizedDigits(placed.toString())
                )
            }
        }
    }

    // ---- selection + editing ----------------------------------------------

    private fun selectZone(id: Long) {
        val zone = zones.find { it.id == id } ?: return
        selectedId = id
        pendingLat = null
        pendingLng = null
        editRadius = zone.radiusM
        syncEditControls()
        render()
        pushDataJs()
        val lat = zone.lat
        val lng = zone.lng
        if (lat != null && lng != null && editRadius > 2000) {
            eval("fitCircle($lat,$lng,$editRadius);")
        }
    }

    private fun syncEditControls() {
        b.tvRadiusValue.text = getString(
            R.string.map_radius, BnDigits.toLocalizedDigits(editRadius.toString())
        )
        b.seekRadius.progress = (editRadius - MIN_RADIUS) / RADIUS_STEP
        for (i in radiusChips.indices) radiusChips[i].isChecked = RADII[i] == editRadius
    }

    private fun setEditRadius(r: Int) {
        editRadius = r.coerceIn(MIN_RADIUS, MAX_RADIUS)
        syncEditControls()
        val id = selectedId ?: return
        val zone = zones.find { it.id == id } ?: return
        eval("updateCircle($id,$editRadius,${zoneActive(zone)});")
        val lat = zone.lat
        val lng = zone.lng
        if (lat != null && lng != null && editRadius > 2000) {
            eval("fitCircle($lat,$lng,$editRadius);")
        }
    }

    private fun saveSelected() {
        val id = selectedId ?: return
        lifecycleScope.launch {
            val zone = Graph.db.safeZoneDao().get(id) ?: return@launch
            Graph.db.safeZoneDao().upsert(zone.copy(radiusM = editRadius))
            zones = zones.map { if (it.id == id) it.copy(radiusM = editRadius) else it }
            Toast.makeText(this@MapActivity, R.string.map_zone_saved, Toast.LENGTH_SHORT).show()
            render()
            pushDataJs()
        }
    }

    private fun deleteSelected() {
        val id = selectedId ?: return
        val zone = zones.find { it.id == id } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(zone.name)
            .setMessage(R.string.map_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    Graph.db.safeZoneDao().delete(id)
                    selectedId = null
                    zones = zones.filter { it.id != id }
                    render()
                    pushDataJs()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Same add-zone flow as the Location screen, but centred on the tapped point. */
    private fun openAddZoneDialog(lat: Double, lng: Double) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val etName = EditText(this).apply {
            hint = getString(R.string.loc_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etRadius = EditText(this).apply {
            hint = getString(R.string.loc_radius_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(editRadius.toString())
        }
        val rgMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbInside = RadioButton(this).apply {
            text = getString(R.string.loc_mode_inside)
            isChecked = true
        }
        val rbAway = RadioButton(this).apply {
            text = getString(R.string.loc_mode_away)
        }
        rgMode.addView(rbInside)
        rgMode.addView(rbAway)
        layout.addView(etName)
        layout.addView(etRadius)
        layout.addView(rgMode)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.loc_add_place)
            .setView(layout)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = etName.text?.toString().orEmpty().trim()
                val radius = etRadius.text?.toString()?.toIntOrNull() ?: 0
                val modeInside = !rbAway.isChecked
                if (name.isEmpty() || radius !in MIN_RADIUS..MAX_RADIUS) {
                    Toast.makeText(this, R.string.map_zone_invalid, Toast.LENGTH_LONG).show()
                } else {
                    lifecycleScope.launch {
                        val id = Graph.db.safeZoneDao().upsert(
                            SafeZone(
                                name = name.take(60),
                                lat = lat,
                                lng = lng,
                                radiusM = radius,
                                modeInside = modeInside,
                                enabled = true
                            )
                        )
                        if (!LocationHelper.hasBackgroundPermission(this@MapActivity)) {
                            Toast.makeText(
                                this@MapActivity,
                                R.string.map_needs_background,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        pendingLat = null
                        pendingLng = null
                        selectedId = id
                        editRadius = radius
                        syncEditControls()
                        Toast.makeText(this@MapActivity, R.string.map_zone_saved, Toast.LENGTH_SHORT).show()
                        render()
                        pushDataJs()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- JS calls -----------------------------------------------------------

    private fun eval(js: String) {
        if (pageReady) {
            try {
                web.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        } else {
            pendingJs.add(js)
        }
    }

    private fun pushDataJs() {
        val arr = JSONArray()
        for (zone in zones) {
            val lat = zone.lat ?: continue
            val lng = zone.lng ?: continue
            val o = JSONObject()
            o.put("id", zone.id)
            o.put("name", zone.name)
            o.put("lat", lat)
            o.put("lng", lng)
            o.put("r", zone.radiusM)
            o.put("active", zoneActive(zone))
            arr.put(o)
        }
        eval("setData($arr,${selectedId ?: -1L});")
    }

    private fun pushMeJs() {
        val fix = meFix ?: return
        eval("setMe(${fix.latitude},${fix.longitude});")
    }

    private fun pushPendingJs() {
        val la = pendingLat
        val lo = pendingLng
        if (la != null && lo != null) eval("setPending($la,$lo);")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- the map page (bundled asset, MapLibre GL JS + CARTO Positron) --------

    private fun mapHtml(): String {
        val raw = try {
            assets.open("maplibre_map.html").bufferedReader().readText()
        } catch (e: Exception) {
            return "<html><body>${getString(R.string.map_webgl_missing)}</body></html>"
        }
        val fix = meFix
        val first = zones.firstOrNull { it.lat != null && it.lng != null }
        val cLat = fix?.latitude ?: first?.lat ?: 23.8103
        val cLng = fix?.longitude ?: first?.lng ?: 90.4125
        return raw
            .replace("__LAT__", cLat.toString())
            .replace("__LNG__", cLng.toString())
            .replace("__MAP_LABEL__", getString(R.string.map_map))
            .replace("__SAT_LABEL__", getString(R.string.map_satellite))
            .replace("__WEBGL_MSG__", getString(R.string.map_webgl_missing))
    }

    companion object {
        private val RADII = intArrayOf(100, 200, 500, 1000, 2000)
        private const val MIN_RADIUS = 50
        private const val MAX_RADIUS = 5000
        private const val RADIUS_STEP = 10
    }
}
