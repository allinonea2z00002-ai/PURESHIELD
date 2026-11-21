package com.pureshield.app

import android.app.Application
import com.pureshield.app.service.KeepAliveService
import com.pureshield.app.service.WorkScheduler
import kotlinx.coroutines.launch

class PureShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Graph.notifier.createChannels()
        WorkScheduler.schedulePeriodic(this)
        WorkScheduler.scheduleDailyReport(this)

        Graph.appScope.launch {
            // Seed the demo defaults on first run, load the filter lists and bring the
            // lock state up to date after any process restart.
            Graph.seedIfFirstRun()
            Graph.blocklist.reload()
            Graph.lockManager.checkpoint()
            // A lock that survived a process death needs its foreground
            // service back, or the next swipe / Home press kills protection
            // again. checkpoint() above already finished an expired lock,
            // so protecting() here is the live answer. Silent no-op when
            // the service cannot start from the background.
            if (Graph.lockManager.protecting()) KeepAliveService.start(this@PureShieldApp)
        }
    }
}
