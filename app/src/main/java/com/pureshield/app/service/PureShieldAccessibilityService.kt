package com.pureshield.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.pureshield.app.Graph
import com.pureshield.app.data.content.ContentWatch
import com.pureshield.app.data.db.BlockEvent
import com.pureshield.app.data.prefs.AppSettings
import com.pureshield.app.util.PermissionPrompter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * App blocker. It only runs after the user turns it on in Android's Accessibility settings,
 * having been shown what it does. It reads only the package name of the app that came to the
 * foreground, plus — while protection is active and uninstall protection is on — whether a
 * Settings/installer screen shows this app's name, and whether a Vivo / MIUI system screen
 * (security centre, permission manager) is open, which can disable or uninstall PURESHIELD.
 * Nothing it sees is stored or sent anywhere.
 *
 * Content filters (all best-effort, all gated by their own switches):
 * - URL watch: when the adult-content filter is on, the address bar shown on screen is checked
 *   against the blocklist (allowlist wins) and a match sends the user home.
 * - Keyword scan: when keyword blocking is on, visible on-screen text is scanned for the user's
 *   keywords (throttled, at most about once every 2 seconds per app).
 * - Reels/Shorts: when the reels switch and the matching per-feed switch are on, the visible
 *   labels of a distracting feed (for example "Shorts" on YouTube) send the user home.
 * A periodic re-check (about every 60 seconds) also re-applies the app-block policy to the
 * foreground app, so a rule change takes effect without an app switch.
 *
 * Power-menu block: while protection is active and the power-menu switch is on, the SystemUI
 * power menu (GlobalActions) is dismissed with Back + Home.
 */
class PureShieldAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var policy: AppBlockPolicy

    @Volatile private var protecting = false
    @Volatile private var uninstallProtection = false
    @Volatile private var powerMenuBlock = false
    @Volatile private var adultFilterOn = false
    @Volatile private var keywordsOn = false
    @Volatile private var reelsOn = false
    @Volatile private var settings: AppSettings? = null
    @Volatile private var keywords: List<String> = emptyList()

    private var lastBlockedPackage = ""
    private var lastBlockedAt = 0L
    private var lastSettingsScan = 0L
    private var lastGuardAt = 0L
    private var lastPowerMenuAt = 0L
    private var lastPowerMenuScanAt = 0L
    private var lastForegroundPackage: String? = null
    private val lastContentScanAt = HashMap<String, Long>()

    private val recheckHandler = Handler(Looper.getMainLooper())
    private val recheckRunnable = object : Runnable {
        override fun run() {
            recheckForeground()
            recheckHandler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        try {
            onServiceConnectedSafe()
        } catch (_: Throwable) {
            // Setup failed partway: the service stays bound and connected.
            // A dead bind is worse than a partial setup — the collectors
            // below retry once each if they were the failing part.
        }
    }

    /**
     * Bind-time setup. `policy` is assigned before anything that can
     * throw, so event handling never sees an uninitialized policy.
     */
    private fun onServiceConnectedSafe() {
        // Accessibility is on now: clear its permission prompt (if any) so
        // the notification disappears even while the app is in the background.
        PermissionPrompter.onAccessibilityGranted(this)
        policy = AppBlockPolicy(this)
        // The blocklist is also used by the URL watch, so load it here too.
        scope.launch { runCatching { Graph.blocklist.reload() } }
        startMainCollector()
        startKeywordsCollector()
        recheckHandler.postDelayed(recheckRunnable, RECHECK_INTERVAL_MS)
    }

    /**
     * Lock state + blocklist + settings collector. A throwing emission
     * (Room/DataStore read error) must never kill the service: it is
     * caught here and retried exactly once after ~10 s. A second failure
     * stays silent — the service stays bound with its last good values.
     */
    private fun startMainCollector(retry: Boolean = true) {
        scope.launch {
            try {
                // 3 flows only: combine() with more than 4 flows fails overload resolution.
                combine(
                    Graph.db.lockStateDao().observe(),
                    Graph.db.blockedAppDao().observeAll(),
                    Graph.settings.flow
                ) { lockState, blockedApps, appSettings ->
                    protecting = (lockState?.active == true) || (lockState?.locProtecting == true)
                    uninstallProtection = appSettings.uninstallProtection
                    powerMenuBlock = appSettings.powerMenuBlock
                    adultFilterOn = appSettings.filterEnabled
                    keywordsOn = appSettings.keywordsEnabled
                    reelsOn = appSettings.reelsEnabled
                    settings = appSettings
                    // App Lock is standalone: a locked app is blocked while a
                    // lock runs OR while the App Lock switch is on. The settings
                    // flow re-emits on every toggle, so the switch (and any
                    // block-list edit) takes effect immediately.
                    policy.lockBlocksApps = protecting || appSettings.appLockEnabled
                    policy.blocked = blockedApps.associateBy { it.packageName }
                }.collect { }
            } catch (_: Throwable) {
                if (retry) {
                    recheckHandler.postDelayed(
                        { if (connected) startMainCollector(retry = false) },
                        COLLECTOR_RETRY_MS
                    )
                }
            }
        }
    }

    /** Keyword collector: same one-retry guard as [startMainCollector]. */
    private fun startKeywordsCollector(retry: Boolean = true) {
        scope.launch {
            try {
                combine(
                    Graph.db.keywordDao().observeAll(),
                    Graph.settings.flow
                ) { keywordRows, _ ->
                    keywords = keywordRows.map { it.word }
                }.collect { }
            } catch (_: Throwable) {
                if (retry) {
                    recheckHandler.postDelayed(
                        { if (connected) startKeywordsCollector(retry = false) },
                        COLLECTOR_RETRY_MS
                    )
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // One bad event must never kill the service: any throw inside the
        // handler (e.g. a stale node from the tree walk) is swallowed here,
        // so the service stays alive for the next event. No event contents
        // are logged.
        try {
            onAccessibilityEventSafe(event)
        } catch (_: Throwable) {
            // Service stays alive for the next event.
        }
    }

    private fun onAccessibilityEventSafe(event: AccessibilityEvent) {
        if (!::policy.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Diagnostics (Home "সার্ভিস স্ট্যাটাস" card): prove the service is
        // alive and receiving window changes. Window-state events only, so
        // the prefs write stays rare (one per app switch, not per content
        // change). Written before the early returns below, so systemui and
        // settings screens count as proof too. The last-event string
        // ("pkg/Class · STATE") is the 4th diagnostics row: next time the
        // power menu is not blocked, it shows exactly which window the
        // service last saw.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val shortPkg = pkg.substringAfterLast('.')
            val shortClass = (event.className?.toString() ?: "?").substringAfterLast('.')
            getSharedPreferences(KeepAliveService.DIAG_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KeepAliveService.KEY_LAST_A11Y_EVENT, System.currentTimeMillis())
                .putString(KeepAliveService.KEY_LAST_A11Y_PKG, pkg)
                .putString(KeepAliveService.KEY_LAST_EVENT, "$shortPkg/$shortClass · STATE")
                .apply()
        }

        // App Lock re-verification: the foreground app changed, so every
        // unlock except the newly foregrounded app's is dropped. Opening
        // Recents (SystemUI) clears the unlock too, so returning from
        // Recents to a locked app asks for authentication again.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != lastForegroundPackage
        ) {
            UnlockSession.retainOnly(pkg)
            lastForegroundPackage = pkg
        }

        // Power menu: any SystemUI package's power window while protection is active.
        // Vivo ships its own SystemUI package, so match by substring, not the AOSP name.
        if (pkg.contains("systemui")) {
            if (protecting && powerMenuBlock) guardPowerMenu(event)
            return
        }
        // Settings family: exact AOSP/Samsung/Vivo packages, plus any installer
        // (covers com.vivo.packageinstaller). The text check in
        // guardSettingsScreen() keeps this safe — it only fires on a screen
        // that actually shows PURESHIELD's name.
        if (pkg in SETTINGS_PACKAGES || pkg.contains("packageinstaller")) {
            if (protecting && uninstallProtection) guardSettingsScreen()
            return
        }
        if (isGuardedSystemApp(pkg)) {
            if (protecting && uninstallProtection) guardSystemApp(pkg)
            return
        }
        if (policy.isNeverBlocked(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The app-block policy wins: a fully blocked app never reaches the content checks.
                policy.reason(pkg)?.let { reason ->
                    block(pkg, reason)
                    return
                }
                checkContent(pkg, throttled = false)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkContent(pkg, throttled = true)
            }
        }
    }

    /**
     * URL watch, keyword scan and Reels/Shorts detection for the foreground app. Content-changed
     * events are throttled per package; window changes always check. Stops at the first match.
     */
    private fun checkContent(pkg: String, throttled: Boolean) {
        if (throttled) {
            val now = System.currentTimeMillis()
            if (now - (lastContentScanAt[pkg] ?: 0L) < CONTENT_SCAN_THROTTLE_MS) return
            lastContentScanAt[pkg] = now
        }
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        // Only judge the app that is actually in front; a background app's content change must
        // never block the foreground app.
        if (root.packageName?.toString() != pkg) return

        // (a) URL watch: when the adult-content filter is on, the address bar shown on screen is checked
        //   against the blocklist (allowlist wins) and a match sends the user home.
        val host = runCatching { ContentWatch.findAddressBarHost(root) }.getOrNull()
        if (host != null) {
            if (adultFilterOn && Graph.blocklist.isBlocked(host)) {
                blockContent(pkg, BlockReason.SITE, recordBlock = true, hostOrKeyword = host)
                return
            }
        }

        val texts = ArrayList<String>()
        runCatching { ContentWatch.collectTexts(root, texts) }
        if (texts.isEmpty()) return

        // (b) Keyword scan: only while keyword blocking is on and keywords exist.
        if (keywordsOn) {
            val hit = ContentWatch.findKeyword(texts, keywords)
            if (hit != null) {
                blockContent(pkg, BlockReason.KEYWORD, recordBlock = false, hostOrKeyword = hit)
                return
            }
        }

        // (c) Reels/Shorts: only while the master switch and that feed's switch are on.
        if (reelsOn) {
            val feed = ContentWatch.detectFeed(pkg, texts)
            val appSettings = settings
            if (feed != null && appSettings != null &&
                Graph.settings.feedEnabled(appSettings, feed.storeKey)
            ) {
                blockContent(pkg, BlockReason.FEED, recordBlock = false, hostOrKeyword = feed.name)
                return
            }
        }

        // (d) YouTube Shorts player: its visible text never says "Shorts"
        //   (the tab bar hides while a Short plays), so a Short opened
        //   from the Home feed slips past (c). A fullscreen portrait
        //   video surface is the player — a regular video is a 16:9
        //   letterbox in portrait. Gated by the same YouTube Shorts switch.
        if (reelsOn && pkg == ContentWatch.PKG_YOUTUBE) {
            val appSettings = settings
            if (appSettings != null && appSettings.youtubeShorts &&
                ContentWatch.hasTallVideoSurface(root, resources.displayMetrics.heightPixels)
            ) {
                blockContent(pkg, BlockReason.FEED, recordBlock = false, hostOrKeyword = "YOUTUBE_SHORTS_PLAYER")
            }
        }
    }

    /** Re-applies the app-block policy to the foreground app, so a rule change fires in place. */
    private fun recheckForeground() {
        // Runs on the main looper: a throw here would kill the process, so
        // guard it the same way as the event handler.
        runCatching {
            if (!::policy.isInitialized) return@runCatching
            val pkg = lastForegroundPackage ?: return@runCatching
            if (pkg == packageName || policy.isNeverBlocked(pkg)) return@runCatching
            val reason = policy.reason(pkg) ?: return@runCatching
            block(pkg, reason)
        }
    }

    /**
     * While protection is active, a Settings or installer screen that shows this app's name (its
     * App info, uninstall confirmation, the accessibility switches) sends the user home. This is
     * the "uninstall protection", and it is stated in the UI. The emergency override always works
     * (it does not go through this screen).
     */
    private fun guardSettingsScreen() {
        val now = System.currentTimeMillis()
        if (now - lastSettingsScan < 700) return
        lastSettingsScan = now
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val found = runCatching { root.findAccessibilityNodeInfosByText(APP_NAME_TEXT) }.getOrNull()
        if (!found.isNullOrEmpty()) block(root.packageName?.toString() ?: "", BlockReason.SELF_PROTECT)
    }

    /**
     * Vivo / MIUI system screens that can disable or uninstall PURESHIELD (security centre,
     * permission manager). Unlike Settings screens, the package match alone is enough here —
     * because these screens never show PURESHIELD's name. Fires only while protection is
     * active and "Uninstall protection" is on.
     *
     * Vivo packages (verified): iManager/security centre = com.iqoo.secure and
     * com.vivo.imanager, permission manager = com.vivo.permissionmanager.
     */
    private fun isGuardedSystemApp(pkg: String): Boolean =
        pkg == "com.iqoo.secure" || pkg == "com.vivo.imanager" ||
            pkg == "com.vivo.permissionmanager" ||
            pkg.contains("securitycenter") || pkg.contains("permissioncontroller") ||
            pkg.contains("permissionmanager")

    private fun guardSystemApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastGuardAt < 1_500) return
        lastGuardAt = now
        block(pkg, BlockReason.SELF_PROTECT)
    }

    /**
     * Power-menu dismiss (best-effort): the SystemUI power menu is the GlobalActions window.
     * Two detectors, because Vivo's swipe menu ("Swipe to power off") does not
     * always arrive as a GlobalActions window-state event:
     * (1) class-name match on window-state events (existing behaviour);
     * (2) visible-text match on the active window ("Swipe to power off" /
     * "Swipe to restart" / "Swipe for SOS" / "Power off"), for state AND
     * content-changed events — the swipe menu may populate its content after
     * the window opens. The text scan is throttled to once a second, and the
     * notification shade (StatusBar / Shade windows) is skipped, so pulling
     * the shade down is never treated as the power menu.
     * Dismissal stays Back + Home, throttled to once per 1.5 s.
     */
    private fun guardPowerMenu(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            POWER_MENU_CLASS_PARTS.any { className.contains(it, ignoreCase = true) }
        ) {
            dismissPowerMenu()
            return
        }
        if (className.contains("statusbar", ignoreCase = true) ||
            className.contains("shade", ignoreCase = true) ||
            className.contains("notification", ignoreCase = true)
        ) return
        val now = System.currentTimeMillis()
        if (now - lastPowerMenuScanAt < POWER_MENU_SCAN_THROTTLE_MS) return
        lastPowerMenuScanAt = now
        if (isPowerMenuText()) dismissPowerMenu()
    }

    private fun dismissPowerMenu() {
        val now = System.currentTimeMillis()
        if (now - lastPowerMenuAt < 1_500) return
        lastPowerMenuAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** True when the active window's visible text looks like a power menu. */
    private fun isPowerMenuText(): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        val texts = ArrayList<String>()
        runCatching { ContentWatch.collectTexts(root, texts) }
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ")
        return POWER_MENU_TEXT_PARTS.any { joined.contains(it, ignoreCase = true) }
    }

    /** Shared 1.5-second de-duplication for every block path, so one screen cannot spam blocks. */
    private fun tryBlock(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPackage && now - lastBlockedAt < 1_500) return false
        lastBlockedPackage = pkg
        lastBlockedAt = now
        return true
    }

    private fun block(pkg: String, reason: BlockReason) {
        if (!tryBlock(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startBlockActivity(pkg, reason)
        // Diagnostics: every block is logged with the reason. LOCK while a
        // time/location lock runs vs standalone App Lock are told apart, so
        // the log shows which one kicked the user out. The stats counter
        // still counts only app blocks (not self-protect), as before.
        val storedReason = if (reason == BlockReason.LOCK && !protecting) "APP_LOCK" else reason.name
        scope.launch {
            runCatching {
                Graph.db.blockEventDao().insert(
                    BlockEvent(
                        packageNameOrHost = pkg,
                        kind = "APP",
                        at = System.currentTimeMillis(),
                        reason = storedReason,
                        pkg = pkg,
                        detail = ""
                    )
                )
                if (reason != BlockReason.SELF_PROTECT) {
                    Graph.stats.recordAppBlock()
                    val app = Graph.db.blockedAppDao().get(pkg)
                    if (app != null) {
                        Graph.db.blockedAppDao().upsert(
                            app.copy(blockCountToday = app.blockCountToday + 1, lastBlockedAt = System.currentTimeMillis())
                        )
                    }
                }
            }
        }
    }

    /**
     * Content block (blocked site, keyword hit, distracting feed). Shown like any other block.
     * A site block is recorded as a website block; keyword/feed blocks are not counted in
     * either total — but every content block is written to the diagnostics log, with the
     * reason and what matched, so the log shows what caught it.
     */
    private fun blockContent(pkg: String, reason: BlockReason, recordBlock: Boolean, hostOrKeyword: String) {
        if (!tryBlock(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startBlockActivity(pkg, reason)
        scope.launch {
            runCatching {
                Graph.db.blockEventDao().insert(
                    BlockEvent(
                        packageNameOrHost = hostOrKeyword,
                        kind = when (reason) {
                            BlockReason.SITE -> "SITE"
                            BlockReason.KEYWORD -> "KEYWORD"
                            else -> "FEED"
                        },
                        at = System.currentTimeMillis(),
                        reason = reason.name,
                        pkg = pkg,
                        detail = hostOrKeyword
                    )
                )
                if (recordBlock) Graph.stats.recordWebsiteBlock()
            }
        }
    }

    private fun startBlockActivity(pkg: String, reason: BlockReason) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        // BlockActivity lives in the UI layer; launch by component name so the engine does not
        // depend on the UI classes at compile time. Contract (see README): the activity reads
        // the extras EXTRA_LABEL (String), EXTRA_REASON (String, a BlockReason name) and
        // EXTRA_PKG (String, the blocked app's package, for the App Lock auth screen).
        startActivity(
            Intent().setClassName(packageName, "com.pureshield.app.ui.block.BlockActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_REASON, reason.name)
                .putExtra(EXTRA_PKG, pkg)
        )
    }

    override fun onInterrupt() {}

    /**
     * Swiped away from Recents: the accessibility service dies with the
     * process, so restart the keep-alive service (which re-anchors the
     * protection) while protection is on. The 5 s alarm is the reliable
     * path; the immediate start is best-effort.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (protecting) {
            KeepAliveService.restartSoon(this)
            runCatching {
                startForegroundService(Intent(this, KeepAliveService::class.java))
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        recheckHandler.removeCallbacks(recheckRunnable)
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var connected: Boolean = false
            private set

        /** Intent extras read by BlockActivity (UI layer). */
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_PKG = "extra_pkg"

        /** Literal app name used by the Settings guard (avoids depending on the UI strings). */
        const val APP_NAME_TEXT = "PURESHIELD"

        private const val CONTENT_SCAN_THROTTLE_MS = 2_000L
        private const val RECHECK_INTERVAL_MS = 60_000L
        /** One delayed retry for a throwing flow collector in onServiceConnected. */
        private const val COLLECTOR_RETRY_MS = 10_000L
        /** At most one power-menu text scan per second (SystemUI content changes are frequent). */
        private const val POWER_MENU_SCAN_THROTTLE_MS = 1_000L

        /** Class-name parts of a power-menu window (AOSP + Vivo). */
        private val POWER_MENU_CLASS_PARTS =
            listOf("GlobalActions", "PowerMenu", "Shutdown", "Reboot", "PowerDialog")

        /** Visible-text phrases of a power menu (Vivo's swipe menu + the AOSP label). */
        private val POWER_MENU_TEXT_PARTS = listOf(
            "Swipe to power off", "Swipe to restart", "Swipe for SOS", "Power off"
        )

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.vivo.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller"
        )
    }
}
