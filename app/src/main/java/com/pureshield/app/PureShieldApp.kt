package com.pureshield.app

import android.app.Application
import android.content.Context
import com.pureshield.app.service.KeepAliveService
import com.pureshield.app.service.WorkScheduler
import kotlinx.coroutines.launch

class PureShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // FIRST: the crash recorder. If anything in this process dies
        // uncaught later (service bind, a tick, a collector), the next
        // Home screen can NAME the crash instead of guessing. Installed
        // before Graph.init so even a startup crash is recorded.
        installCrashRecorder()
        Graph.init(this)
        Graph.notifier.createChannels()
        WorkScheduler.schedulePeriodic(this)
        WorkScheduler.scheduleDailyReport(this)

        Graph.appScope.launch {
            // Seed the demo defaults on first run, load the filter lists and bring the
            // lock state up to date after any process restart.
            runCatching { Graph.seedIfFirstRun() }
            runCatching { Graph.blocklist.reload() }
            runCatching { Graph.lockManager.checkpoint() }
            // A lock that survived a process death needs its foreground
            // service back, or the next swipe / Home press kills protection
            // again. checkpoint() above already finished an expired lock,
            // so protecting() here is the live answer. Silent no-op when
            // the service cannot start from the background.
            if (runCatching { Graph.lockManager.protecting() }.getOrDefault(false)) {
                KeepAliveService.start(this@PureShieldApp)
            }
        }
    }

    /**
     * Records the next uncaught crash as one short line in the "diag"
     * prefs (see KeepAliveService.KEY_LAST_CRASH) and then chains to the
     * previous default handler, so the process still dies normally.
     * Never stores a stack trace, event contents, or any user data — just
     * "<Exception>: <first message line, max ~120 chars>".
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val name = throwable.javaClass.simpleName
                    .takeIf { it.isNotEmpty() } ?: "Throwable"
                val msg = (throwable.message ?: "")
                    .replace('\n', ' ').replace('\r', ' ')
                    .take(120).trim()
                val line = if (msg.isEmpty()) name else "$name: $msg"
                applicationContext.getSharedPreferences(
                    KeepAliveService.DIAG_PREFS, Context.MODE_PRIVATE
                ).edit()
                    .putString(KeepAliveService.KEY_LAST_CRASH, line)
                    .putLong(KeepAliveService.KEY_LAST_CRASH_AT, System.currentTimeMillis())
                    .apply()
            }
            // Chain on: the crash must still kill the process normally,
            // never leave the app hanging in a half-dead state.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
