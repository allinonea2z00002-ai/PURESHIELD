package com.pureshield.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.data.StatsCalculator
import com.pureshield.app.domain.LockManager
import com.pureshield.app.location.PlaceWatcher
import com.pureshield.app.util.DayUtils
import com.pureshield.app.util.Notifier
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** WorkManager scheduling. Work survives process death and reboot (WorkManager re-registers it). */
object WorkScheduler {
    private const val PERIODIC = "pureshield_guard"
    private const val UNLOCK = "pureshield_unlock"
    private const val DAILY_REPORT = "pureshield_daily_report"

    /** Every 15 minutes (the Android minimum): checkpoint + location presence check. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<GuardWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One-shot work near the expected end of a time lock, so it ends without needing the app open. */
    fun scheduleUnlock(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<UnlockWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNLOCK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelUnlock(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNLOCK)
    }

    /** Daily summary, once a day (WorkManager picks the exact time within the 24 h window). */
    fun scheduleDailyReport(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyReportWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(DAILY_REPORT, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class GuardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.lockManager.checkpoint()
        runCatching { PlaceWatcher.checkNow(applicationContext) }
        runCatching { RepeatLock.checkAndStart(applicationContext) }
        // Prune old rows so the database stays small.
        val now = System.currentTimeMillis()
        runCatching { Graph.db.blockEventDao().deleteOlderThan(now - 30L * 86_400_000L) }
        runCatching { Graph.db.zoneEventDao().deleteOlderThan(now - 30L * 86_400_000L) }
        return Result.success()
    }
}

/**
 * Repeat time locks (Time Lock screen: "যে দিনগুলোতে আবার লক হবে").
 * Runs from GuardWorker (every 15 min): on a selected day, at/after the user's usual
 * start time (the time of their last manual lock, default 09:00), starts a lock with
 * the last-used duration — at most once per day. The demo names no repeat time, so the
 * usual-start-time rule is the coordinator's documented choice.
 */
object RepeatLock {
    suspend fun checkAndStart(context: Context) {
        val settings = Graph.settings.get()
        val mask = settings.repeatDaysMask
        if (mask == 0) return
        val calendar = java.util.Calendar.getInstance()
        val bit = calendar.get(java.util.Calendar.DAY_OF_WEEK) % 7 // Sat=0 ... Fri=6
        if ((mask and (1 shl bit)) == 0) return
        val todayKey = DayUtils.dateKey(System.currentTimeMillis())
        if (settings.lastRepeatDate == todayKey) return
        val startAt = java.time.LocalTime.of(
            settings.lastLockStartHour.coerceIn(0, 23),
            settings.lastLockStartMinute.coerceIn(0, 59)
        )
        if (java.time.LocalTime.now().isBefore(startAt)) return
        if (Graph.lockManager.snapshotNow().active) return
        val minutes = settings.lastLockMinutes.coerceIn(3, 1440).toLong()
        if (Graph.lockManager.start(minutes) == LockManager.StartResult.Started) {
            Graph.settings.setLastRepeatDate(todayKey)
        }
    }
}

class UnlockWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Graph.lockManager.checkpoint()
        return Result.success()
    }
}

class DailyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Graph.settings.get()
        if (!settings.dailyReport) return Result.success()
        val rows = Graph.db.focusDayDao().getAll()
        val summary = StatsCalculator.summarize(rows, LocalDate.now())
        val todayKey = DayUtils.dateKey(System.currentTimeMillis())
        val blocksToday = Graph.db.blockEventDao().countOnDate(todayKey)
        Graph.notifier.daily(
            Notifier.ID_DAILY,
            applicationContext.getString(R.string.notif_daily_title),
            applicationContext.getString(
                R.string.notif_daily_text,
                summary.todayMinutes,
                blocksToday
            )
        )
        return Result.success()
    }
}
