package com.pureshield.app.ui.blocking

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.db.BlockedApp
import com.pureshield.app.databinding.ActivityAppPickerBinding
import com.pureshield.app.databinding.ItemAppRowBinding
import com.pureshield.app.ui.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installed-apps picker ("+ অ্যাপ যোগ"): every installed launcher app that
 * is not already blocked. Tapping one adds it to the block list for real
 * (Room) — the demo's toast-only add is not acceptable here.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppPickerBinding

    private var allApps: List<BlockingActivity.InstalledApp> = emptyList()
    private var query: String = ""
    private val adapter = PickerAdapter { app -> addApp(app) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvBack.setOnClickListener { finish() }
        b.rvPicker.layoutManager = LinearLayoutManager(this)
        b.rvPicker.adapter = adapter
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                render()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        lifecycleScope.launch {
            val blocked = Graph.db.blockedAppDao().getAll()
                .filter { it.blocked }
                .map { it.packageName }
                .toHashSet()
            allApps = loadInstalledApps().filter { it.packageName !in blocked }
            render()
        }
    }

    private fun render() {
        val q = query.trim().lowercase()
        val shown = if (q.isEmpty()) allApps
        else allApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        adapter.submit(shown)
        b.rvPicker.isVisible = shown.isNotEmpty()
        b.tvEmpty.isVisible = shown.isEmpty()
    }

    private fun addApp(app: BlockingActivity.InstalledApp) {
        lifecycleScope.launch {
            // Defence in depth: no new block-list entries while a lock is running.
            if (Graph.lockManager.protecting()) {
                Toast.makeText(this@AppPickerActivity, R.string.locked_change_blocked, Toast.LENGTH_LONG).show()
                return@launch
            }
            Graph.db.blockedAppDao().upsert(
                BlockedApp(
                    packageName = app.packageName,
                    label = app.label,
                    blocked = true
                )
            )
            Toast.makeText(
                this@AppPickerActivity,
                getString(R.string.block_app_added, app.label),
                Toast.LENGTH_SHORT
            ).show()
            allApps = allApps.filter { it.packageName != app.packageName }
            render()
        }
    }

    private suspend fun loadInstalledApps(): List<BlockingActivity.InstalledApp> =
        withContext(Dispatchers.IO) {
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
                else BlockingActivity.InstalledApp(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString()
                )
            }.sortedBy { it.label.lowercase() }
        }

    private inner class PickerAdapter(
        private val onPick: (BlockingActivity.InstalledApp) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.Holder>() {

        private var rows: List<BlockingActivity.InstalledApp> = emptyList()

        fun submit(newRows: List<BlockingActivity.InstalledApp>) {
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
            fun bind(app: BlockingActivity.InstalledApp) {
                vb.tvName.text = app.label
                vb.tvSub.text = app.packageName
                vb.ivIcon.setImageDrawable(
                    runCatching {
                        packageManager.getApplicationIcon(app.packageName)
                    }.getOrNull() ?: getDrawable(android.R.drawable.sym_def_app_icon)
                )
                vb.switchApp.isVisible = false
                vb.root.setOnClickListener { onPick(app) }
            }
        }
    }
}
