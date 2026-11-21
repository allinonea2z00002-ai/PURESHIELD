package com.pureshield.app.ui.blocking

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.db.BlockedApp
import com.pureshield.app.data.db.BlockedSite
import com.pureshield.app.data.db.Keyword
import com.pureshield.app.databinding.ActivityBlockingBinding
import com.pureshield.app.databinding.ItemAppRowBinding
import com.pureshield.app.ui.BottomNav
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App & Website blocking screen (demo Screen 4, verbatim labels).
 *
 * Everything is real and Room-backed: the app list is every installed
 * launcher app with a live toggle, "আজ N বার ব্লক হয়েছে" counts come from
 * [BlockedApp.blockCountToday] (honest 0 at first run — never the demo's
 * hard-coded 6/9/4), sites and keywords are add/remove lists, and the
 * Private DNS row deliberately shows NO fake "চালু" tag: the app cannot
 * reliably read the phone's Private DNS hostname, so it shows the setup
 * guide instead. First-run defaults come from [Graph.seedIfFirstRun].
 */
class BlockingActivity : AppCompatActivity() {

    private lateinit var b: ActivityBlockingBinding

    private var blockedApps: List<BlockedApp> = emptyList()
    private var sites: List<BlockedSite> = emptyList()
    private var keywords: List<Keyword> = emptyList()
    private var installed: List<InstalledApp> = emptyList()
    private var updatingSwitches = false

    /** True while any protection is active (time session OR location lock).
     *  Block-list edits are refused while this is true — the lock must end first. */
    private var lockActive = false

    private val adapter = AppAdapter { app, on -> onAppToggle(app, on) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityBlockingBinding.inflate(layoutInflater)
        setContentView(b.root)
        BottomNav.bind(this, BottomNav.Tab.BLOCK)

        b.rvApps.layoutManager = LinearLayoutManager(this)
        b.rvApps.adapter = adapter

        b.btnAddApp.setOnClickListener {
            if (lockActive) {
                Toast.makeText(this, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        b.btnAddSite.setOnClickListener { addSiteFromInput() }
        b.etSite.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addSiteFromInput()
                true
            } else false
        }
        b.btnAddKeyword.setOnClickListener { addKeywordFromInput() }
        b.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeywordFromInput()
                true
            } else false
        }
        b.btnDnsGuide.setOnClickListener { showDnsGuide() }

        bindReelSwitch(
            b.switchReelsYoutube,
            { on -> Graph.settings.setYoutubeShorts(on) },
            getString(R.string.block_reels_youtube)
        )
        bindReelSwitch(
            b.switchReelsInstagram,
            { on -> Graph.settings.setInstagramReels(on) },
            getString(R.string.block_reels_instagram)
        )
        bindReelSwitch(
            b.switchReelsFacebook,
            { on -> Graph.settings.setFacebookReels(on) },
            getString(R.string.block_reels_facebook)
        )

        // App Lock master switch. Unlike the Reels switches it is NOT refused
        // mid-lock: during a lock, protection already dominates
        // (protecting || appLockEnabled), so flipping it changes nothing
        // until the lock ends — the user's explicit choice then applies.
        b.switchAppLock.setOnCheckedChangeListener { _, on ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                Graph.settings.setAppLockEnabled(on)
                Toast.makeText(
                    this@BlockingActivity,
                    getString(
                        if (on) R.string.block_toggle_on else R.string.block_toggle_off,
                        getString(R.string.applock_title)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        lifecycleScope.launch {
            installed = loadInstalledApps()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        Graph.db.blockedAppDao().observeAll(),
                        Graph.db.blockedSiteDao().observeAll(),
                        Graph.db.keywordDao().observeAll()
                    ) { apps, doms, kws -> Triple(apps, doms, kws) }
                        .collect { (apps, doms, kws) ->
                            blockedApps = apps
                            sites = doms
                            keywords = kws
                            renderCounts()
                            renderSiteChips()
                            renderKeywordChips()
                            renderAppList()
                        }
                }
                launch {
                    Graph.db.lockStateDao().observe().collect { lock ->
                        lockActive = lock?.active == true || lock?.locProtecting == true
                    }
                }
                launch {
                    Graph.settings.flow.collect { settings ->
                        updatingSwitches = true
                        try {
                            b.switchAppLock.isChecked = settings.appLockEnabled
                            b.switchReelsYoutube.isChecked = settings.youtubeShorts
                            b.switchReelsInstagram.isChecked = settings.instagramReels
                            b.switchReelsFacebook.isChecked = settings.facebookReels
                        } finally {
                            updatingSwitches = false
                        }
                    }
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

    // ---- rendering ----------------------------------------------------------

    private fun renderCounts() {
        val apps = blockedApps.count { it.blocked }
        b.tvAppsCount.text = getString(
            R.string.block_apps_count, BnDigits.toLocalizedDigits(apps.toString())
        )
        b.tvSitesCount.text = getString(
            R.string.block_sites_count, BnDigits.toLocalizedDigits(sites.size.toString())
        )
    }

    private fun renderAppList() {
        val blockedByPkg = blockedApps.associateBy { it.packageName }
        val rows = installed.map { app ->
            val row = blockedByPkg[app.packageName]
            InstalledAppRow(
                app = app,
                blocked = row?.blocked == true,
                todayCount = row?.blockCountToday ?: 0
            )
        }.sortedWith(
            compareByDescending<InstalledAppRow> { it.blocked }.thenBy { it.app.label.lowercase() }
        )
        adapter.submit(rows)
        b.rvApps.isVisible = rows.isNotEmpty()
        b.tvNoApps.isVisible = rows.isEmpty()
    }

    private fun renderSiteChips() {
        b.chipGroupSites.removeAllViews()
        for (site in sites) {
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_site_chip, b.chipGroupSites, false) as Chip
            chip.text = site.host
            chip.setOnCloseIconClickListener {
                if (lockActive) {
                    Toast.makeText(this, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
                    return@setOnCloseIconClickListener
                }
                lifecycleScope.launch {
                    Graph.db.blockedSiteDao().delete(site.host)
                    Toast.makeText(
                        this@BlockingActivity,
                        getString(R.string.block_site_removed, site.host),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            b.chipGroupSites.addView(chip)
        }
    }

    private fun renderKeywordChips() {
        b.chipGroupKeywords.removeAllViews()
        for (kw in keywords) {
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_site_chip, b.chipGroupKeywords, false) as Chip
            chip.text = kw.word
            chip.setOnCloseIconClickListener {
                if (lockActive) {
                    Toast.makeText(this, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
                    return@setOnCloseIconClickListener
                }
                lifecycleScope.launch {
                    Graph.db.keywordDao().delete(kw.word)
                    Toast.makeText(
                        this@BlockingActivity,
                        getString(R.string.block_kw_removed, kw.word),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            b.chipGroupKeywords.addView(chip)
        }
    }

    // ---- app toggles ----------------------------------------------------------

    private fun onAppToggle(row: InstalledAppRow, on: Boolean) {
        if (lockActive) {
            Toast.makeText(this, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
            // Re-render from the database so the row switch flips back.
            renderAppList()
            return
        }
        lifecycleScope.launch {
            val dao = Graph.db.blockedAppDao()
            val existing = dao.get(row.app.packageName)
            if (existing != null) {
                dao.setBlocked(row.app.packageName, on)
            } else if (on) {
                dao.upsert(
                    BlockedApp(
                        packageName = row.app.packageName,
                        label = row.app.label,
                        blocked = true
                    )
                )
            }
            Toast.makeText(
                this@BlockingActivity,
                getString(
                    if (on) R.string.block_app_added else R.string.block_app_removed,
                    row.app.label
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---- sites ----------------------------------------------------------

    private fun addSiteFromInput() {
        if (lockActive) {
            Toast.makeText(this, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
            return
        }
        val raw = b.etSite.text?.toString().orEmpty()
        val host = normalizeDomain(raw)
        when {
            raw.isBlank() -> Toast.makeText(this, R.string.block_site_empty, Toast.LENGTH_SHORT).show()
            host == null -> Toast.makeText(this, R.string.block_site_invalid, Toast.LENGTH_LONG).show()
            sites.any { it.host.equals(host, ignoreCase = true) } ->
                Toast.makeText(this, R.string.block_site_duplicate, Toast.LENGTH_SHORT).show()
            else -> {
                lifecycleScope.launch {
                    Graph.db.blockedSiteDao().upsert(
                        BlockedSite(host = host, addedAt = System.currentTimeMillis())
                    )
                    b.etSite.text = null
                    Toast.makeText(
                        this@BlockingActivity,
                        getString(R.string.block_site_added, host),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun normalizeDomain(raw: String): String? {
        var s = raw.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (s.isEmpty() || s.contains(' ') || !s.contains('.')) return null
        if (!s.matches(Regex("[a-z0-9.-]+"))) return null
        if (s.startsWith(".") || s.endsWith(".")) return null
        return s
    }

    // ---- keywords ----------------------------------------------------------

    private fun addKeywordFromInput() {
        if (lockActive) {
            Toast.makeText(this, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
            return
        }
        val raw = b.etKeyword.text?.toString().orEmpty().trim().lowercase()
        when {
            raw.isEmpty() -> Toast.makeText(this, R.string.block_kw_empty, Toast.LENGTH_SHORT).show()
            keywords.any { it.word.equals(raw, ignoreCase = true) } ->
                Toast.makeText(this, R.string.block_kw_duplicate, Toast.LENGTH_SHORT).show()
            else -> {
                lifecycleScope.launch {
                    Graph.db.keywordDao().upsert(Keyword(word = raw))
                    b.etKeyword.text = null
                    Toast.makeText(
                        this@BlockingActivity,
                        getString(R.string.block_kw_added, raw),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ---- Private DNS ----------------------------------------------------------

    /**
     * No fake "চালু" tag: the app cannot reliably read the phone's Private DNS
     * hostname, so this shows the setup steps and opens network settings.
     */
    private fun showDnsGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.block_dns_guide_title)
            .setMessage(R.string.block_dns_guide_msg)
            .setPositiveButton(R.string.block_dns_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- reels switches ----------------------------------------------------------

    private fun bindReelSwitch(
        switch: com.google.android.material.switchmaterial.SwitchMaterial,
        save: suspend (Boolean) -> Unit,
        feedName: String
    ) {
        switch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (lockActive) {
                // Revert the tap: Reels/Shorts blocking cannot be weakened mid-lock.
                updatingSwitches = true
                try {
                    switch.isChecked = !on
                } finally {
                    updatingSwitches = false
                }
                Toast.makeText(this, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch {
                save(on)
                Toast.makeText(
                    this@BlockingActivity,
                    getString(
                        if (on) R.string.block_toggle_on else R.string.block_toggle_off,
                        feedName
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---- installed apps ----------------------------------------------------------

    private suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        infos.mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) null
            else InstalledApp(
                packageName = pkg,
                label = info.loadLabel(pm).toString()
            )
        }.sortedBy { it.label.lowercase() }
    }

    // ---- list ----------------------------------------------------------

    data class InstalledApp(val packageName: String, val label: String)

    data class InstalledAppRow(
        val app: InstalledApp,
        val blocked: Boolean,
        val todayCount: Int
    )

    private inner class AppAdapter(
        private val onToggle: (InstalledAppRow, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {

        private var rows: List<InstalledAppRow> = emptyList()

        fun submit(newRows: List<InstalledAppRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemAppRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val vb: ItemAppRowBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(row: InstalledAppRow) {
                vb.tvName.text = row.app.label
                vb.tvSub.text = if (row.blocked) {
                    getString(
                        R.string.block_app_today,
                        BnDigits.toLocalizedDigits(row.todayCount.toString())
                    )
                } else {
                    getString(R.string.block_app_not_blocked)
                }
                vb.ivIcon.setImageDrawable(
                    runCatching {
                        packageManager.getApplicationIcon(row.app.packageName)
                    }.getOrNull() ?: getDrawable(android.R.drawable.sym_def_app_icon)
                )
                vb.switchApp.setOnCheckedChangeListener(null)
                vb.switchApp.isChecked = row.blocked
                vb.switchApp.setOnCheckedChangeListener { _, on -> onToggle(row, on) }
            }
        }
    }
}
