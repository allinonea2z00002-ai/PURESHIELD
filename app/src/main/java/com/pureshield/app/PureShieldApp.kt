package com.pureshield.app

import android.app.Application
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
        }
    }
}
