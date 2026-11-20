package com.pureshield.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import com.google.android.material.card.MaterialCardView
import com.pureshield.app.R
import com.pureshield.app.ui.blocking.BlockingActivity
import com.pureshield.app.ui.location.LocationActivity
import com.pureshield.app.ui.stats.StatsActivity
import com.pureshield.app.ui.timelock.TimeLockActivity

/** Resolves a theme colour attribute (e.g. R.attr.mintColor) to its ARGB value. */
internal fun Context.attrColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.data
}

/** Resolves a theme dimension attribute (e.g. R.attr.iconTileRadius) to pixels. */
internal fun Context.attrDimension(@AttrRes attr: Int): Float {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return 0f
    return TypedValue.complexToDimension(value.data, resources.displayMetrics)
}

/** Resolves a theme boolean attribute, returning [default] when it is not set. */
internal fun Context.attrBoolean(@AttrRes attr: Int, default: Boolean): Boolean {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return default
    return value.data != 0
}

/**
 * Shared bottom nav (see include_bottom_nav.xml). Every tabbed activity calls
 * [bind] right after setContentView with its own [Tab]; the active tab is
 * mint, the others faint/sub. The centre raised shield FAB is the Time Lock
 * tab. Tapping the current tab does nothing; other tabs are brought forward
 * instead of stacking.
 *
 * [current] may be null (e.g. Settings, which has no tab): nothing is
 * highlighted then. When the activity's layout has no nav include at all,
 * this is a no-op.
 */
object BottomNav {

    enum class Tab { HOME, LOCATION, TIME_LOCK, BLOCK, STATS }

    fun bind(activity: AppCompatActivity, current: Tab?) {
        val navHome = activity.findViewById<LinearLayout>(R.id.navHome) ?: return
        val navLocation = activity.findViewById<LinearLayout>(R.id.navLocation) ?: return
        val navBlock = activity.findViewById<LinearLayout>(R.id.navBlock) ?: return
        val navStats = activity.findViewById<LinearLayout>(R.id.navStats) ?: return
        val fabTimeLock = activity.findViewById<AppCompatImageView>(R.id.fabTimeLock) ?: return
        val homeIcon = activity.findViewById<AppCompatImageView>(R.id.homeIcon) ?: return
        val homeLabel = activity.findViewById<TextView>(R.id.homeLabel) ?: return
        val locationIcon = activity.findViewById<AppCompatImageView>(R.id.locationIcon) ?: return
        val locationLabel = activity.findViewById<TextView>(R.id.locationLabel) ?: return
        val blockIcon = activity.findViewById<AppCompatImageView>(R.id.blockIcon) ?: return
        val blockLabel = activity.findViewById<TextView>(R.id.blockLabel) ?: return
        val statsIcon = activity.findViewById<AppCompatImageView>(R.id.statsIcon) ?: return
        val statsLabel = activity.findViewById<TextView>(R.id.statsLabel) ?: return

        val mint = activity.attrColor(R.attr.mintColor)
        val faint = activity.attrColor(R.attr.faintColor)
        val sub = activity.attrColor(R.attr.subColor)

        paint(homeIcon, homeLabel, current == Tab.HOME, mint, faint, sub)
        paint(locationIcon, locationLabel, current == Tab.LOCATION, mint, faint, sub)
        paint(blockIcon, blockLabel, current == Tab.BLOCK, mint, faint, sub)
        paint(statsIcon, statsLabel, current == Tab.STATS, mint, faint, sub)

        // Tab-bar + FAB glow (demo §2.4/§2.5: FAB 0 10px 24px green — cyan in
        // Holo; bar 0 14px 34px with the theme shadow colour; Zen stays flat).
        val navBar = activity.findViewById<MaterialCardView>(R.id.navBar)
        navBar?.let { Polish.glowBottomNav(it, fabTimeLock, activity) }

        navHome.setOnClickListener { go(activity, current, Tab.HOME, MainActivity::class.java) }
        navLocation.setOnClickListener { go(activity, current, Tab.LOCATION, LocationActivity::class.java) }
        fabTimeLock.setOnClickListener { go(activity, current, Tab.TIME_LOCK, TimeLockActivity::class.java) }
        navBlock.setOnClickListener { go(activity, current, Tab.BLOCK, BlockingActivity::class.java) }
        navStats.setOnClickListener { go(activity, current, Tab.STATS, StatsActivity::class.java) }
    }

    private fun paint(
        icon: AppCompatImageView,
        label: TextView,
        active: Boolean,
        mint: Int,
        faint: Int,
        sub: Int
    ) {
        icon.imageTintList = ColorStateList.valueOf(if (active) mint else faint)
        label.setTextColor(if (active) mint else sub)
    }

    private fun go(activity: AppCompatActivity, current: Tab?, target: Tab, cls: Class<*>) {
        if (current == target) return
        val intent = Intent(activity, cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        activity.startActivity(intent)
    }
}
