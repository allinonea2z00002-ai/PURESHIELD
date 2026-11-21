package com.pureshield.app

import android.content.Context
import com.pureshield.app.data.StatsRepository
import com.pureshield.app.data.blocklist.BlocklistManager
import com.pureshield.app.data.db.AppDatabase
import com.pureshield.app.data.db.BlockedApp
import com.pureshield.app.data.db.BlockedSite
import com.pureshield.app.data.db.Keyword
import com.pureshield.app.data.db.SafeZone
import com.pureshield.app.data.prefs.SecureStore
import com.pureshield.app.data.prefs.SettingsKeys
import com.pureshield.app.data.prefs.SettingsStore
import com.pureshield.app.domain.LockManager
import com.pureshield.app.service.UsageRepository
import com.pureshield.app.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tiny service locator. Created once in [PureShieldApp.onCreate], before any service, receiver or
 * worker runs, so every component can use the same database and engine.
 */
object Graph {
    private lateinit var appContext: Context

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val db: AppDatabase by lazy { AppDatabase.get(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val secure: SecureStore by lazy { SecureStore(appContext) }
    val notifier: Notifier by lazy { Notifier(appContext) }
    val stats: StatsRepository by lazy { StatsRepository(db.focusDayDao()) }
    val blocklist: BlocklistManager by lazy { BlocklistManager(appContext, db) }
    val usage: UsageRepository by lazy { UsageRepository(appContext) }
    val lockManager: LockManager by lazy {
        LockManager(appContext, db, stats, notifier, settings, secure)
    }

    /**
     * Seeds the first-run defaults (see the task spec). Runs once: guarded by the
     * SettingsStore firstRun flag, which is cleared at the end. Safe to call repeatedly.
     */
    suspend fun seedIfFirstRun() {
        if (!settings.get().firstRun) return

        // Blocked apps: the demo's defaults. Free Fire is listed but NOT blocked.
        val apps = listOf(
            BlockedApp("com.facebook.katana", "Facebook", blocked = true),
            BlockedApp("com.instagram.android", "Instagram", blocked = true),
            BlockedApp("com.google.android.youtube", "YouTube", blocked = true),
            BlockedApp("com.zhiliaoapp.musically", "TikTok", blocked = true),
            BlockedApp("com.android.chrome", "Chrome", blocked = true),
            BlockedApp("com.dts.freefireth", "Free Fire", blocked = false)
        )
        apps.forEach { db.blockedAppDao().upsert(it) }

        // Blocked sites.
        val now = System.currentTimeMillis()
        listOf("facebook.com", "tiktok.com", "instagram.com", "betting-site.example").forEach {
            db.blockedSiteDao().upsert(BlockedSite(host = it, addedAt = now))
        }

        // Keywords.
        listOf("adult", "casino", "betting", "dating").forEach {
            db.keywordDao().upsert(Keyword(word = it))
        }

        // SafeZone templates: coordinates are null until the user saves the location
        // by tapping the place (e.g. on the map). They never drive the lock until then.
        db.safeZoneDao().upsert(SafeZone(name = "বাড়ি", radiusM = 100, modeInside = true))
        db.safeZoneDao().upsert(SafeZone(name = "বাজার", radiusM = 150, modeInside = true))

        // Guards + Reels toggles.
        settings.set(SettingsKeys.LOCATION_LOCK_ENABLED, true)
        settings.set(SettingsKeys.POWER_MENU_BLOCK, true)
        settings.set(SettingsKeys.UNINSTALL_PROTECTION, true)
        settings.set(SettingsKeys.STRICT_MODE, false)
        settings.set(SettingsKeys.APP_LOCK_ENABLED, true)
        settings.set(SettingsKeys.SETTINGS_GUARD, true)
        settings.set(SettingsKeys.BOUNDARY_ALERT, true)
        settings.set(SettingsKeys.DAILY_REPORT, true)
        settings.set(SettingsKeys.YOUTUBE_SHORTS, true)
        settings.set(SettingsKeys.INSTAGRAM_REELS, true)
        settings.set(SettingsKeys.INSTAGRAM_STORIES, false)
        settings.set(SettingsKeys.WHATSAPP_STATUS, false)
        settings.set(SettingsKeys.WHATSAPP_CHANNELS, false)
        settings.set(SettingsKeys.SNAPCHAT_SPOTLIGHT, true)
        settings.set(SettingsKeys.SNAPCHAT_STORIES, false)
        settings.set(SettingsKeys.WA_BUSINESS_STATUS, false)
        settings.set(SettingsKeys.WA_BUSINESS_CHANNELS, false)
        settings.set(SettingsKeys.FACEBOOK_REELS, false)

        settings.setFirstRun(false)
    }
}
