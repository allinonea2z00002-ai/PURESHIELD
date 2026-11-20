package com.pureshield.app.ui.history

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
import com.pureshield.app.data.db.SafeZone
import com.pureshield.app.data.db.ZoneEvent
import com.pureshield.app.databinding.ActivityRouteHistoryBinding
import com.pureshield.app.databinding.ItemHistoryRowBinding
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Route & history (Settings > "রুট ও হিস্ট্রি (৩০ দিন)"): zone entry/exit
 * events from the last 30 days, newest first, with an honest empty state
 * when there is nothing yet.
 *
 * [ZoneEvent] stores only a zone id, so names are resolved from the current
 * [SafeZone] list (a deleted zone falls back to its id — nothing is
 * invented). Note: the engine has no lock-session table, so this screen
 * lists zone events only; there are no focus-session rows to merge in.
 */
class RouteHistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityRouteHistoryBinding

    private val adapter = HistoryAdapter()
    private val timeFormat =
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale("bn"))

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityRouteHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvBack.setOnClickListener { finish() }
        b.rvHistory.layoutManager = LinearLayoutManager(this)
        b.rvHistory.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    Graph.db.zoneEventDao().observeRecent(500),
                    Graph.db.safeZoneDao().observeAll()
                ) { zoneEvents, zones -> Pair(zoneEvents, zones) }
                    .collect { (zoneEvents, zones) ->
                        val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                        val names = zones.associateBy { it.id }
                        val rows = zoneEvents
                            .filter { it.at >= since }
                            .map { event ->
                                val name = names[event.zoneId]?.name
                                    ?: BnDigits.toLocalizedDigits(event.zoneId.toString())
                                HistoryRow(
                                    title = getString(
                                        if (event.entered) R.string.history_entered
                                        else R.string.history_exited,
                                        name
                                    ),
                                    epoch = event.at,
                                    entered = event.entered
                                )
                            }
                            .sortedByDescending { it.epoch }
                        adapter.submit(rows)
                        b.rvHistory.isVisible = rows.isNotEmpty()
                        b.tvEmpty.isVisible = rows.isEmpty()
                    }
            }
        }
    }

    private fun formatTime(epoch: Long): String {
        val zoned = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
        return timeFormat.format(zoned)
    }

    data class HistoryRow(val title: String, val epoch: Long, val entered: Boolean)

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private var rows: List<HistoryRow> = emptyList()

        fun submit(newRows: List<HistoryRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemHistoryRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val vb: ItemHistoryRowBinding) :
            RecyclerView.ViewHolder(vb.root) {
            fun bind(row: HistoryRow) {
                vb.tvTitle.text = row.title
                vb.tvTime.text = formatTime(row.epoch)
                val colorRes = if (row.entered) R.attr.mintColor else R.attr.redColor
                val tv = android.util.TypedValue()
                val color = if (theme.resolveAttribute(colorRes, tv, true)) tv.data
                else android.graphics.Color.GRAY
                vb.dotHistory.setCardBackgroundColor(color)
            }
        }
    }
}
