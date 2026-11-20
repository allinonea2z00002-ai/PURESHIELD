package com.pureshield.app.service

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import com.pureshield.app.data.db.BlockedApp

enum class BlockReason {
    LOCK, SITE, KEYWORD, FEED, SELF_PROTECT
}

/**
 * Decides whether opening an app should be blocked. The cache is refreshed from the database by
 * the accessibility service, so decisions never touch the database on the event path.
 *
 * The demo has one rule only: while protection is active ([lockBlocksApps]), an app the user
 * marked as blocked is blocked. There are no profiles, modes, conditions or usage limits in
 * this engine — those were Stay Focused features, deliberately omitted.
 */
class AppBlockPolicy(private val context: Context) {

    @Volatile var lockBlocksApps: Boolean = false

    /** Blocked apps by package name, refreshed from BlockedAppDao. */
    @Volatile var blocked: Map<String, BlockedApp> = emptyMap()

    /** Phone, emergency, system UI and launcher stay reachable at all times, whatever the user selects. */
    private val neverBlock: Set<String> by lazy { buildNeverBlock() }

    /** The block reason for [packageName], or null when the app may open. */
    fun reason(packageName: String): BlockReason? {
        if (packageName in neverBlock) return null
        if (!lockBlocksApps) return null
        val rule = blocked[packageName] ?: return null
        if (!rule.blocked) return null
        return BlockReason.LOCK
    }

    fun isNeverBlocked(packageName: String) = packageName in neverBlock

    private fun buildNeverBlock(): Set<String> {
        val pm = context.packageManager
        val set = hashSetOf(
            context.packageName,
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.dialer",
            "com.samsung.android.contacts"
        )
        runCatching {
            context.getSystemService(Context.TELECOM_SERVICE)
                .let { it as? TelecomManager }?.defaultDialerPackage?.let(set::add)
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("tel:123")), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        return set
    }
}
