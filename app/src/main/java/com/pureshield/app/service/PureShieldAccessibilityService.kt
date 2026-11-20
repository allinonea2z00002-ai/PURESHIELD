package com.pureshield.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.pureshield.app.Graph
import com.pureshield.app.data.content.ContentWatch
import com.pureshield.app.data.db.BlockEvent
import com.pureshield.app.data.prefs.AppSettings
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
        policy = AppBlockPolicy(this)
        // The blocklist is also used by the URL watch, so load it here too.
        scope.launch { runCatching { Graph.blocklist.reload() } }
        scope.launch {
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
                policy.lockBlocksApps = protecting
                policy.blocked = blockedApps.associateBy { it.packageName }
            }.collect { }
        }
        scope.launch {
            combine(
                Graph.db.keywordDao().observeAll(),
                Graph.settings.flow
            ) { keywordRows, _ ->
                keywords = keywordRows.map { it.word }
            }.collect { }
        }
        recheckHandler.postDelayed(recheckRunnable, RECHECK_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::policy.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Power menu: SystemUI GlobalActions window while protection is active.
        if (pkg == "com.android.systemui") {
            if (protecting && powerMenuBlock) guardPowerMenu(event)
            return
        }
        if (pkg in SETTINGS_PACKAGES) {
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
                lastForegroundPackage = pkg
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
        val root = rootInActiveWindow ?: return
        // Only judge the app that is actually in front; a background app's content change must
        // never block the foreground app.
        if (root.packageName?.toString() != pkg) return

        // (a) URL watch: when the adult-content filter is on, the address bar shown on screen is checked
        //   against the blocklist (allowlist wins) and a match sends the user home.
        val host = ContentWatch.findAddressBarHost(root)
        if (host != null) {
            if (adultFilterOn && Graph.blocklist.isBlocked(host)) {
                blockContent(pkg, BlockReason.SITE, recordBlock = true, hostOrKeyword = host)
                return
            }
        }

        val texts = ArrayList<String>()
        ContentWatch.collectTexts(root, texts)
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
            }
        }
    }

    /** Re-applies the app-block policy to the foreground app, so a rule change fires in place. */
    private fun recheckForeground() {
        if (!::policy.isInitialized) return
        val pkg = lastForegroundPackage ?: return
        if (pkg == packageName || policy.isNeverBlocked(pkg)) return
        val reason = policy.reason(pkg) ?: return
        block(pkg, reason)
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
        val root = rootInActiveWindow ?: return
        val found = root.findAccessibilityNodeInfosByText(APP_NAME_TEXT)
        if (!found.isNullOrEmpty()) block(root.packageName?.toString() ?: "", BlockReason.SELF_PROTECT)
    }

    /**
     * Vivo / MIUI system screens that can disable or uninstall PURESHIELD (security centre,
     * permission manager). Unlike Settings screens, the package match alone is enough here —
     * because these screens never show PURESHIELD's name. Fires only while protection is
     * active and "Uninstall protection" is on.
     */
    private fun isGuardedSystemApp(pkg: String): Boolean =
        pkg.contains("securitycenter") || pkg.contains("permissioncontroller")

    private fun guardSystemApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastGuardAt < 1_500) return
        lastGuardAt = now
        block(pkg, BlockReason.SELF_PROTECT)
    }

    /**
     * Power-menu dismiss (best-effort): the SystemUI power menu is the GlobalActions window.
     * The class name check keeps the notification shade and other SystemUI windows untouched.
     */
    private fun guardPowerMenu(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val className = event.className?.toString() ?: ""
        if (!className.contains("GlobalActions", ignoreCase = true) &&
            !className.contains("PowerMenu", ignoreCase = true)
        ) return
        val now = System.currentTimeMillis()
        if (now - lastPowerMenuAt < 1_500) return
        lastPowerMenuAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
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
        if (reason != BlockReason.SELF_PROTECT) {
            scope.launch {
                runCatching {
                    Graph.db.blockEventDao().insert(
                        BlockEvent(packageNameOrHost = pkg, kind = "APP", at = System.currentTimeMillis())
                    )
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
     * either total.
     */
    private fun blockContent(pkg: String, reason: BlockReason, recordBlock: Boolean, hostOrKeyword: String) {
        if (!tryBlock(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startBlockActivity(pkg, reason)
        if (recordBlock) {
            scope.launch {
                runCatching {
                    Graph.db.blockEventDao().insert(
                        BlockEvent(packageNameOrHost = hostOrKeyword, kind = "SITE", at = System.currentTimeMillis())
                    )
                    Graph.stats.recordWebsiteBlock()
                }
            }
        }
    }

    private fun startBlockActivity(pkg: String, reason: BlockReason) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        // BlockActivity lives in the UI layer; launch by component name so the engine does not
        // depend on the UI classes at compile time. Contract (see README): the activity reads
        // the extras EXTRA_LABEL (String) and EXTRA_REASON (String, a BlockReason name).
        startActivity(
            Intent().setClassName(packageName, "com.pureshield.app.ui.block.BlockActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_REASON, reason.name)
        )
    }

    override fun onInterrupt() {}

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

        /** Literal app name used by the Settings guard (avoids depending on the UI strings). */
        const val APP_NAME_TEXT = "PURESHIELD"

        private const val CONTENT_SCAN_THROTTLE_MS = 2_000L
        private const val RECHECK_INTERVAL_MS = 60_000L

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller"
        )
    }
}
