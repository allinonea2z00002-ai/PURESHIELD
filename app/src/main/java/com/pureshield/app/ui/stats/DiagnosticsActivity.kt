package com.pureshield.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.db.BlockEvent
import com.pureshield.app.databinding.ActivityDiagnosticsBinding
import com.pureshield.app.databinding.ItemDiagRowBinding
import com.pureshield.app.ui.BottomNav
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.util.BnDigits
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Diagnostics log: every block the service fired, newest first — what time,
 * which app the user was kicked out of, why (a running lock, App Lock, a
 * blocked site, a keyword, Reels/Shorts, or app protection) and what
 * matched (the site, the keyword or the feed). Rows written before the v2
 * columns exist show the reason from their kind instead. The log is pruned
 * to 30 days by the daily worker; the Clear button wipes it at once.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiagnosticsBinding
    private val adapter = DiagAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        BottomNav.bind(this, BottomNav.Tab.STATS)

        b.rvDiag.layoutManager = LinearLayoutManager(this)
        b.rvDiag.adapter = adapter

        b.btnClear.setOnClickListener {
            lifecycleScope.launch {
                runCatching { Graph.db.blockEventDao().deleteAll() }
                Toast.makeText(this@DiagnosticsActivity, R.string.diag_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.db.blockEventDao().observeRecent(200).collect { rows ->
                    adapter.submit(rows)
                    b.rvDiag.isVisible = rows.isNotEmpty()
                    b.tvEmpty.isVisible = rows.isEmpty()
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

    /** "২৪ নভে, ৫:২২ PM" style timestamp in the app language. */
    private fun formatTime(at: Long): String {
        val sdf = SimpleDateFormat("d MMM, h:mm a", BnDigits.appLocale())
        return BnDigits.toLocalizedDigits(sdf.format(Date(at)))
    }

    /** App the user was kicked out of; old rows fall back to the stored package/host. */
    private fun appLabel(event: BlockEvent): String {
        val pkg = event.pkg.ifBlank { null }
        if (pkg != null) {
            runCatching {
                val info = packageManager.getApplicationInfo(pkg, 0)
                return packageManager.getApplicationLabel(info).toString()
            }
        }
        if (event.kind == "APP") {
            runCatching {
                val info = packageManager.getApplicationInfo(event.packageNameOrHost, 0)
                return packageManager.getApplicationLabel(info).toString()
            }
        }
        return event.packageNameOrHost
    }

    /** Reason key: v2 rows carry it; old rows derive it from the kind. */
    private fun reasonKey(event: BlockEvent): String {
        if (event.reason.isNotBlank()) return event.reason
        return event.kind
    }

    private fun reasonLabel(event: BlockEvent): String = when (reasonKey(event)) {
        "APP_LOCK" -> getString(R.string.diag_reason_app_lock)
        "SITE" -> getString(R.string.diag_reason_site)
        "KEYWORD" -> getString(R.string.diag_reason_keyword)
        "FEED" -> getString(R.string.diag_reason_feed)
        "SELF_PROTECT" -> getString(R.string.diag_reason_self)
        else -> getString(R.string.diag_reason_lock)
    }

    private fun detailText(event: BlockEvent): String? {
        if (event.detail.isBlank()) return null
        return when (event.reason) {
            "SITE" -> getString(R.string.diag_detail_site, event.detail)
            "KEYWORD" -> getString(R.string.diag_detail_keyword, event.detail)
            "FEED" -> getString(R.string.diag_detail_feed, event.detail)
            else -> event.detail
        }
    }

    private inner class DiagAdapter : RecyclerView.Adapter<DiagAdapter.Holder>() {

        private var rows: List<BlockEvent> = emptyList()

        fun submit(newRows: List<BlockEvent>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemDiagRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val vb: ItemDiagRowBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(event: BlockEvent) {
                vb.tvApp.text = appLabel(event)
                vb.tvTime.text = formatTime(event.at)
                vb.tvReason.text = reasonLabel(event)
                val detail = detailText(event)
                vb.tvDetail.isVisible = detail != null
                vb.tvDetail.text = detail ?: ""
            }
        }
    }
}
