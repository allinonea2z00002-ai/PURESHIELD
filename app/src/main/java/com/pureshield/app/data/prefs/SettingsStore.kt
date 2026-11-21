package com.pureshield.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "pureshield_prefs")

/** All PURESHIELD settings. Theme lives in ThemeManager (its own DataStore file), not here. */
data class AppSettings(
    /** Location lock master switch (the ONE location-lock setting). */
    val locationLockEnabled: Boolean = true,
    /** Block the power menu while protection is active. */
    val powerMenuBlock: Boolean = true,
    /** Kick the user out of Settings / installer / security-centre screens while locked. */
    val uninstallProtection: Boolean = true,
    /** Strict mode: extra hardening (no emergency override without the partner PIN). */
    val strictMode: Boolean = false,
    /** Standalone App Lock: a locked app asks for PIN / biometrics even with
     *  no time lock running. The block list only exists to lock apps, so the
     *  default is ON. */
    val appLockEnabled: Boolean = true,
    /** Guard the app's own Settings screens while a lock is active. */
    val settingsGuard: Boolean = true,
    /** Notify shortly before entering / leaving a saved place. */
    val boundaryAlert: Boolean = true,
    /** Send the daily summary notification. */
    val dailyReport: Boolean = true,
    /** "en" or "bn". */
    val language: String = "bn",
    /** True while the premium trial is running. */
    val premiumTrial: Boolean = false,
    /** True until the first-run seed has run. */
    val firstRun: Boolean = true,
    /** Adult-content filter master (accessibility URL-watch + the phone's Private DNS). */
    val filterEnabled: Boolean = true,
    /** Keyword screen-text scan master. */
    val keywordsEnabled: Boolean = true,
    /** Reels / Shorts blocking master. */
    val reelsEnabled: Boolean = true,
    /** Per-feed Reels / Shorts switches. YouTube Shorts, Instagram Reels,
     *  Snapchat Spotlight, Facebook Reels and Telegram Stories are ON;
     *  the rest are OFF. (Facebook Reels was OFF until the 2026-11-21
     *  fix round — the user reported Lite Reels playing freely — so it
     *  now defaults ON, with a one-time migration for existing installs.) */
    val youtubeShorts: Boolean = true,
    val instagramReels: Boolean = true,
    val instagramStories: Boolean = false,
    val whatsappStatus: Boolean = false,
    val whatsappChannels: Boolean = false,
    val snapchatSpotlight: Boolean = true,
    val snapchatStories: Boolean = false,
    val waBusinessStatus: Boolean = false,
    val waBusinessChannels: Boolean = false,
    val facebookReels: Boolean = true,
    val telegramStories: Boolean = true,
    /** Time Lock "Location Lock-এর সাথে মিলিয়ে" switch: when on, leaving a safe zone
     *  ends a running time lock (time OR distance, whichever first). Mirrored from the
     *  Time Lock screen's own store so the engine/worker can read it. */
    val combineWithLocation: Boolean = true,
    /** Repeat-day bitmask (Sat=bit0 ... Fri=bit6). Demo default: Sun+Mon+Wed = 22. */
    val repeatDaysMask: Int = 22,
    /** Duration (minutes) the repeat lock re-starts with. */
    val lastLockMinutes: Int = 10,
    /** Usual start time of the last manual lock — the repeat lock fires at/after this. */
    val lastLockStartHour: Int = 9,
    val lastLockStartMinute: Int = 0,
    /** yyyy-MM-dd of the last day a repeat lock was started (one per day). */
    val lastRepeatDate: String = ""
)

object SettingsKeys {
    val LOCATION_LOCK_ENABLED = booleanPreferencesKey("location_lock_enabled")
    val POWER_MENU_BLOCK = booleanPreferencesKey("power_menu_block")
    val UNINSTALL_PROTECTION = booleanPreferencesKey("uninstall_protection")
    val STRICT_MODE = booleanPreferencesKey("strict_mode")
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
    val SETTINGS_GUARD = booleanPreferencesKey("settings_guard")
    val BOUNDARY_ALERT = booleanPreferencesKey("boundary_alert")
    val DAILY_REPORT = booleanPreferencesKey("daily_report")
    val LANGUAGE = stringPreferencesKey("language")
    val PREMIUM_TRIAL = booleanPreferencesKey("premium_trial")
    val FIRST_RUN = booleanPreferencesKey("first_run")
    val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
    val KEYWORDS_ENABLED = booleanPreferencesKey("keywords_enabled")
    val REELS_ENABLED = booleanPreferencesKey("reels_enabled")
    val YOUTUBE_SHORTS = booleanPreferencesKey("youtube_shorts")
    val INSTAGRAM_REELS = booleanPreferencesKey("instagram_reels")
    val INSTAGRAM_STORIES = booleanPreferencesKey("instagram_stories")
    val WHATSAPP_STATUS = booleanPreferencesKey("whatsapp_status")
    val WHATSAPP_CHANNELS = booleanPreferencesKey("whatsapp_channels")
    val SNAPCHAT_SPOTLIGHT = booleanPreferencesKey("snapchat_spotlight")
    val SNAPCHAT_STORIES = booleanPreferencesKey("snapchat_stories")
    val WA_BUSINESS_STATUS = booleanPreferencesKey("wa_business_status")
    val WA_BUSINESS_CHANNELS = booleanPreferencesKey("wa_business_channels")
    val FACEBOOK_REELS = booleanPreferencesKey("facebook_reels")
    val TELEGRAM_STORIES = booleanPreferencesKey("telegram_stories")
    /** Set once by [SettingsStore.migrateFacebookReelsDefault]. */
    val FB_REELS_MIGRATED_V1 = booleanPreferencesKey("fb_reels_migrated_v1")
    val COMBINE_WITH_LOCATION = booleanPreferencesKey("combine_with_location")
    val REPEAT_DAYS_MASK = intPreferencesKey("repeat_days_mask")
    val LAST_LOCK_MINUTES = intPreferencesKey("last_lock_minutes")
    val LAST_LOCK_START_HOUR = intPreferencesKey("last_lock_start_hour")
    val LAST_LOCK_START_MINUTE = intPreferencesKey("last_lock_start_minute")
    val LAST_REPEAT_DATE = stringPreferencesKey("last_repeat_date")
}

/** App preferences kept in DataStore. */
class SettingsStore(private val context: Context) {

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            locationLockEnabled = p[SettingsKeys.LOCATION_LOCK_ENABLED] ?: true,
            powerMenuBlock = p[SettingsKeys.POWER_MENU_BLOCK] ?: true,
            uninstallProtection = p[SettingsKeys.UNINSTALL_PROTECTION] ?: true,
            strictMode = p[SettingsKeys.STRICT_MODE] ?: false,
            appLockEnabled = p[SettingsKeys.APP_LOCK_ENABLED] ?: true,
            settingsGuard = p[SettingsKeys.SETTINGS_GUARD] ?: true,
            boundaryAlert = p[SettingsKeys.BOUNDARY_ALERT] ?: true,
            dailyReport = p[SettingsKeys.DAILY_REPORT] ?: true,
            language = p[SettingsKeys.LANGUAGE] ?: "bn",
            premiumTrial = p[SettingsKeys.PREMIUM_TRIAL] ?: false,
            firstRun = p[SettingsKeys.FIRST_RUN] ?: true,
            filterEnabled = p[SettingsKeys.FILTER_ENABLED] ?: true,
            keywordsEnabled = p[SettingsKeys.KEYWORDS_ENABLED] ?: true,
            reelsEnabled = p[SettingsKeys.REELS_ENABLED] ?: true,
            youtubeShorts = p[SettingsKeys.YOUTUBE_SHORTS] ?: true,
            instagramReels = p[SettingsKeys.INSTAGRAM_REELS] ?: true,
            instagramStories = p[SettingsKeys.INSTAGRAM_STORIES] ?: false,
            whatsappStatus = p[SettingsKeys.WHATSAPP_STATUS] ?: false,
            whatsappChannels = p[SettingsKeys.WHATSAPP_CHANNELS] ?: false,
            snapchatSpotlight = p[SettingsKeys.SNAPCHAT_SPOTLIGHT] ?: true,
            snapchatStories = p[SettingsKeys.SNAPCHAT_STORIES] ?: false,
            waBusinessStatus = p[SettingsKeys.WA_BUSINESS_STATUS] ?: false,
            waBusinessChannels = p[SettingsKeys.WA_BUSINESS_CHANNELS] ?: false,
            facebookReels = p[SettingsKeys.FACEBOOK_REELS] ?: true,
            telegramStories = p[SettingsKeys.TELEGRAM_STORIES] ?: true,
            combineWithLocation = p[SettingsKeys.COMBINE_WITH_LOCATION] ?: true,
            repeatDaysMask = p[SettingsKeys.REPEAT_DAYS_MASK] ?: 22,
            lastLockMinutes = p[SettingsKeys.LAST_LOCK_MINUTES] ?: 10,
            lastLockStartHour = p[SettingsKeys.LAST_LOCK_START_HOUR] ?: 9,
            lastLockStartMinute = p[SettingsKeys.LAST_LOCK_START_MINUTE] ?: 0,
            lastRepeatDate = p[SettingsKeys.LAST_REPEAT_DATE] ?: ""
        )
    }

    suspend fun get(): AppSettings = flow.first()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    suspend fun setLocationLockEnabled(value: Boolean) = set(SettingsKeys.LOCATION_LOCK_ENABLED, value)
    suspend fun setPowerMenuBlock(value: Boolean) = set(SettingsKeys.POWER_MENU_BLOCK, value)
    suspend fun setUninstallProtection(value: Boolean) = set(SettingsKeys.UNINSTALL_PROTECTION, value)
    suspend fun setStrictMode(value: Boolean) = set(SettingsKeys.STRICT_MODE, value)
    suspend fun setAppLockEnabled(value: Boolean) = set(SettingsKeys.APP_LOCK_ENABLED, value)
    suspend fun setSettingsGuard(value: Boolean) = set(SettingsKeys.SETTINGS_GUARD, value)
    suspend fun setBoundaryAlert(value: Boolean) = set(SettingsKeys.BOUNDARY_ALERT, value)
    suspend fun setDailyReport(value: Boolean) = set(SettingsKeys.DAILY_REPORT, value)
    suspend fun setLanguage(value: String) = set(SettingsKeys.LANGUAGE, value)
    suspend fun setPremiumTrial(value: Boolean) = set(SettingsKeys.PREMIUM_TRIAL, value)
    suspend fun setFirstRun(value: Boolean) = set(SettingsKeys.FIRST_RUN, value)
    suspend fun setFilterEnabled(value: Boolean) = set(SettingsKeys.FILTER_ENABLED, value)
    suspend fun setKeywordsEnabled(value: Boolean) = set(SettingsKeys.KEYWORDS_ENABLED, value)
    suspend fun setReelsEnabled(value: Boolean) = set(SettingsKeys.REELS_ENABLED, value)
    suspend fun setYoutubeShorts(value: Boolean) = set(SettingsKeys.YOUTUBE_SHORTS, value)
    suspend fun setInstagramReels(value: Boolean) = set(SettingsKeys.INSTAGRAM_REELS, value)
    suspend fun setInstagramStories(value: Boolean) = set(SettingsKeys.INSTAGRAM_STORIES, value)
    suspend fun setWhatsappStatus(value: Boolean) = set(SettingsKeys.WHATSAPP_STATUS, value)
    suspend fun setWhatsappChannels(value: Boolean) = set(SettingsKeys.WHATSAPP_CHANNELS, value)
    suspend fun setSnapchatSpotlight(value: Boolean) = set(SettingsKeys.SNAPCHAT_SPOTLIGHT, value)
    suspend fun setSnapchatStories(value: Boolean) = set(SettingsKeys.SNAPCHAT_STORIES, value)
    suspend fun setWaBusinessStatus(value: Boolean) = set(SettingsKeys.WA_BUSINESS_STATUS, value)
    suspend fun setWaBusinessChannels(value: Boolean) = set(SettingsKeys.WA_BUSINESS_CHANNELS, value)
    suspend fun setFacebookReels(value: Boolean) = set(SettingsKeys.FACEBOOK_REELS, value)
    suspend fun setTelegramStories(value: Boolean) = set(SettingsKeys.TELEGRAM_STORIES, value)
    suspend fun setCombineWithLocation(value: Boolean) = set(SettingsKeys.COMBINE_WITH_LOCATION, value)
    suspend fun setRepeatDaysMask(value: Int) = set(SettingsKeys.REPEAT_DAYS_MASK, value)
    suspend fun setLastLockMinutes(value: Int) = set(SettingsKeys.LAST_LOCK_MINUTES, value)
    suspend fun setLastLockStart(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[SettingsKeys.LAST_LOCK_START_HOUR] = hour
            it[SettingsKeys.LAST_LOCK_START_MINUTE] = minute
        }
    }
    suspend fun setLastRepeatDate(value: String) = set(SettingsKeys.LAST_REPEAT_DATE, value)

    /** True when the per-feed switch for [feedStoreKey] is on. */
    fun feedEnabled(settings: AppSettings, feedStoreKey: String): Boolean = when (feedStoreKey) {
        FEED_YOUTUBE_SHORTS -> settings.youtubeShorts
        FEED_INSTAGRAM_REELS -> settings.instagramReels
        FEED_INSTAGRAM_STORIES -> settings.instagramStories
        FEED_WHATSAPP_STATUS -> settings.whatsappStatus
        FEED_WHATSAPP_CHANNELS -> settings.whatsappChannels
        FEED_SNAPCHAT_SPOTLIGHT -> settings.snapchatSpotlight
        FEED_SNAPCHAT_STORIES -> settings.snapchatStories
        FEED_WA_BUSINESS_STATUS -> settings.waBusinessStatus
        FEED_WA_BUSINESS_CHANNELS -> settings.waBusinessChannels
        FEED_FACEBOOK_REELS -> settings.facebookReels
        FEED_TELEGRAM_STORIES -> settings.telegramStories
        else -> false
    }

    /**
     * One-time migration: Facebook Reels was seeded OFF, so phones that
     * already ran the app have `false` stored and a new default alone
     * would never reach them. The first launch of the build that carries
     * this flips it ON once; a later manual OFF stays OFF (the flag is
     * already set, so this never runs again).
     */
    suspend fun migrateFacebookReelsDefault() {
        context.settingsDataStore.edit { prefs ->
            if (prefs[SettingsKeys.FB_REELS_MIGRATED_V1] != true) {
                prefs[SettingsKeys.FACEBOOK_REELS] = true
                prefs[SettingsKeys.FB_REELS_MIGRATED_V1] = true
            }
        }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { it.clear() }
    }

    companion object {
        const val FEED_YOUTUBE_SHORTS = "youtube_shorts"
        const val FEED_INSTAGRAM_REELS = "instagram_reels"
        const val FEED_INSTAGRAM_STORIES = "instagram_stories"
        const val FEED_WHATSAPP_STATUS = "whatsapp_status"
        const val FEED_WHATSAPP_CHANNELS = "whatsapp_channels"
        const val FEED_SNAPCHAT_SPOTLIGHT = "snapchat_spotlight"
        const val FEED_SNAPCHAT_STORIES = "snapchat_stories"
        const val FEED_WA_BUSINESS_STATUS = "wa_business_status"
        const val FEED_WA_BUSINESS_CHANNELS = "wa_business_channels"
        const val FEED_FACEBOOK_REELS = "facebook_reels"
        const val FEED_TELEGRAM_STORIES = "telegram_stories"
    }
}
