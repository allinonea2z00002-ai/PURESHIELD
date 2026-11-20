package com.pureshield.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pureshield.app.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restarts protection after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { Graph.blocklist.reload() }
                Graph.lockManager.recoverAfterRestart()
                // Restart the keep-alive service when a session or the location lock is on.
                if (runCatching { Graph.lockManager.protecting() }.getOrDefault(false)) {
                    KeepAliveService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
