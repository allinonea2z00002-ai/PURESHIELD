package com.pureshield.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.location.LocationHelper
import com.pureshield.app.service.PureShieldAccessibilityService

/**
 * One permission the lock needs, its notification id, its Home-card /
 * notification wording, and the settings screen that grants it.
 */
enum class PermissionKind(
    val notifId: Int,
    @StringRes val titleRes: Int,
    @StringRes val textRes: Int,
    /** When set, tapping the notification (or the Home-card Allow button)
     *  grants this permission with the system dialog inside the app instead
     *  of opening Settings — only runtime permissions can be granted that
     *  way; the special kinds below keep their settings deep-link. */
    val requestKey: String? = null
) {
    ACCESSIBILITY(2001, R.string.perm_accessibility_title, R.string.perm_accessibility_text) {
        override fun intent(context: Context) = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    },
    USAGE(2002, R.string.perm_usage_title, R.string.perm_usage_text) {
        override fun intent(context: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    },
    LOCATION_FINE(2003, R.string.perm_location_title, R.string.perm_location_text, "location") {
        override fun intent(context: Context) = appDetailsIntent(context)
    },
    LOCATION_BACKGROUND(2004, R.string.perm_background_title, R.string.perm_background_text) {
        override fun intent(context: Context) = appDetailsIntent(context)
    },
    NOTIFICATIONS(2005, R.string.perm_notifications_title, R.string.perm_notifications_text, "notifications") {
        override fun intent(context: Context) =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    },
    BATTERY(2006, R.string.perm_battery_title, R.string.perm_battery_text) {
        override fun intent(context: Context) = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    };

    /** The settings screen (or system dialog) that grants this permission. */
    abstract fun intent(context: Context): Intent

    /**
     * Notification tap target. Runtime kinds open MainActivity, which fires
     * the same in-app system dialog; special kinds open their settings
     * screen directly (Android lets no app grant those itself).
     */
    fun notifIntent(context: Context): Intent {
        val key = requestKey
            ?: return intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Intent()
            .setClassName(context.packageName, "com.pureshield.app.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_REQUEST_PERMISSION, key)
    }
}

/** App-details settings, where Location ("Allow all the time" included) is granted. */
private fun appDetailsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/**
 * Permission prompts for the lock. When protection is turned on, every
 * permission the lock still needs gets ONE notification that opens exactly
 * that permission's settings screen — and the same missing list is shown on
 * the Home card, so nothing is lost when notifications themselves are off.
 *
 * No spam: a notification is posted only the first time a permission is
 * found missing (remembered in SharedPreferences). It is cancelled as soon
 * as the permission is granted; a dismissed notification is not re-posted
 * until the permission becomes missing again after having been granted.
 */
object PermissionPrompter {

    /** Intent extra telling MainActivity to fire an in-app permission dialog
     *  on resume; value is the kind's [PermissionKind.requestKey]
     *  ("location" / "notifications"). */
    const val EXTRA_REQUEST_PERMISSION = "extra_request_permission"

    private const val PREFS = "perm_prompts"
    private const val KEY_NOTIFIED = "notified_kinds"

    /**
     * Cold start: forget the permissions that are STILL missing, so their
     * notifications come back automatically. A once-dismissed prompt must
     * not stay silent forever — that is why the user felt nothing ever came.
     * Within one session nothing is re-posted (only onCreate calls this).
     */
    suspend fun resetStillMissing(context: Context) {
        val missingNames = missing(context).map { it.name }.toSet()
        if (missingNames.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet())?.toMutableSet() ?: return
        if (notified.removeAll(missingNames)) {
            prefs.edit().putStringSet(KEY_NOTIFIED, notified).apply()
        }
    }

    /** Every permission the lock needs right now that is still missing. */
    suspend fun missing(context: Context): List<PermissionKind> {
        val out = ArrayList<PermissionKind>()
        if (!isAccessibilityEnabled(context)) out += PermissionKind.ACCESSIBILITY
        if (!Graph.usage.hasAccess()) out += PermissionKind.USAGE
        val settings = Graph.settings.get()
        val places = Graph.db.safeZoneDao().getAll()
        // Location is only required when the location lock is on or a real
        // place is saved (a template with no coordinates does not count).
        val locationNeeded = settings.locationLockEnabled ||
            places.any { it.enabled && it.lat != null && it.lng != null }
        if (locationNeeded) {
            if (!LocationHelper.hasForegroundPermission(context)) {
                out += PermissionKind.LOCATION_FINE
            } else if (!LocationHelper.hasBackgroundPermission(context)) {
                out += PermissionKind.LOCATION_BACKGROUND
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            out += PermissionKind.NOTIFICATIONS
        }
        if (!isBatteryExempt(context)) out += PermissionKind.BATTERY
        return out
    }

    /**
     * Posts notifications for newly-missing permissions and cancels the ones
     * that are granted now. Call when protection is turned on, and on app
     * resume (the user may just have granted something in Settings).
     */
    suspend fun checkAndPrompt(context: Context) {
        sync(context, missing(context))
    }

    /** Post for [missing], cancel for everything else that was notified. */
    fun sync(context: Context, missing: List<PermissionKind>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val missingNames = missing.map { it.name }.toSet()
        val notificationsJustGranted = PermissionKind.NOTIFICATIONS.name in notified &&
            PermissionKind.NOTIFICATIONS !in missing
        // Grants first: cancel + forget.
        for (kind in PermissionKind.entries) {
            if (kind.name in notified && kind.name !in missingNames) {
                Graph.notifier.cancel(kind.notifId)
                notified.remove(kind.name)
            }
        }
        // Notifications themselves were just switched on: the other prompts
        // can finally be seen, so forget them and post them below.
        if (notificationsJustGranted) notified.clear()
        // Post only newly-missing kinds — never re-post a dismissed one.
        for (kind in missing) {
            if (kind.name !in notified) {
                Graph.notifier.permissionPrompt(
                    kind.notifId,
                    context.getString(kind.titleRes),
                    context.getString(kind.textRes),
                    kind.notifIntent(context)
                )
                notified.add(kind.name)
            }
        }
        prefs.edit().putStringSet(KEY_NOTIFIED, notified).apply()
    }

    /**
     * Accessibility just connected, so its permission is granted: clear its
     * prompt even while the app is in the background.
     */
    fun onAccessibilityGranted(context: Context) {
        Graph.notifier.cancel(PermissionKind.ACCESSIBILITY.notifId)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet())?.toMutableSet() ?: return
        if (notified.remove(PermissionKind.ACCESSIBILITY.name)) {
            prefs.edit().putStringSet(KEY_NOTIFIED, notified).apply()
        }
    }

    /** True when our accessibility service is in the enabled-services list. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(context, PureShieldAccessibilityService::class.java)
            .flattenToString()
        return enabled.split(':').any { it == expected }
    }

    /** True when the system ignores battery optimizations for this app. */
    fun isBatteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
