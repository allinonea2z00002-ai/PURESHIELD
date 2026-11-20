package com.pureshield.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pureshield.app.Graph
import com.pureshield.app.util.DayUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Counts real screen unlocks: every USER_PRESENT broadcast is one unlock.
 * (UsageStats can only report SCREEN_INTERACTIVE — screen wake-ups — so this counter is
 * the honest unlock count used by the Analytics screen.)
 */
object UnlockCounter {
    private const val PREFS = "pureshield_unlocks"

    fun increment(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = DayUtils.dateKey(System.currentTimeMillis())
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun countToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(DayUtils.dateKey(System.currentTimeMillis()), 0)
    }
}

/** Screen events (unlock counting) and wall-clock changes. */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> UnlockCounter.increment(context)
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                // Screen on/off alone is not an unlock; USER_PRESENT is the unlock signal.
            }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // Not in the manifest filter (see note in the final report); handled here
                // anyway so a future manifest registration just works.
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        Graph.lockManager.onSystemTimeChanged()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
