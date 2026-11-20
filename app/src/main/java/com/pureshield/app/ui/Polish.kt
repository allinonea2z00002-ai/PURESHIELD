package com.pureshield.app.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.pureshield.app.R
import com.pureshield.app.ui.widget.SceneryView

/**
 * Visual-polish helpers (demo findings §2 / §3 / §4).
 *
 * Glow rule (demo-verified): glow goes on graphics / ring / buttons / cards,
 * NEVER on letterforms — the demo has zero `text-shadow`, and no helper here
 * touches a TextView's paint. Coloured glow is delivered through the view
 * outline shadow (`outlineSpotShadowColor` / `outlineAmbientShadowColor`,
 * API 28+; ignored on older runtimes) plus elevation, because Android
 * elevation alone can only draw black shadows.
 *
 * Per-theme glow colours (demo §2.6 — the demo's hardcoded-green quirks are
 * reproduced as-is): primary buttons / FAB / selected chips / avatar keep
 * the demo's green shadow in every theme except Holo (cyan) and Zen (no
 * shadow at all — `hideShadows`). Card shadows use the theme's `shadowColor`
 * attr, which was defined per theme but consumed nowhere until now.
 */
object Polish {

    /** Demo primary-button shadow: 0 10px 22px rgba(31,122,95,.35). */
    @ColorInt const val GLOW_GREEN_BUTTON: Int = 0x591F7A5F
    /** Demo FAB shadow: 0 10px 24px rgba(31,122,95,.4). */
    @ColorInt const val GLOW_GREEN_FAB: Int = 0x661F7A5F
    /** Demo selected-chip shadow: 0 6px 14px rgba(31,122,95,.28). */
    @ColorInt const val GLOW_GREEN_CHIP: Int = 0x471F7A5F
    /** Demo Holo button glow: 0 0 20px rgba(0,229,255,.35). */
    @ColorInt const val GLOW_CYAN: Int = 0x5900E5FF
    /** Demo Holo FAB glow (cyan at the FAB's .4 alpha). */
    @ColorInt const val GLOW_CYAN_FAB: Int = 0x6600E5FF
    /** Demo Dawn avatar shadow: 0 6px 16px rgba(160,90,60,.35). */
    @ColorInt const val GLOW_WARM_AVATAR: Int = 0x59A05A3C
    /** Demo ring drop-shadow: 0 0 10px rgba(47,158,126,.35). */
    @ColorInt const val GLOW_GREEN_RING: Int = 0x592F9E7E
    /** Demo Holo ring glow: drop-shadow(0 0 12px rgba(0,229,255,.55)). */
    @ColorInt const val GLOW_CYAN_STRONG: Int = 0x8C00E5FF.toInt()
    /** Demo Holo glass card: background rgba(22,29,61,.55). */
    @ColorInt const val HOLO_GLASS_BG: Int = 0x8C161D3D.toInt()
    /** Demo Holo glass border: rgba(0,229,255,.35). */
    @ColorInt const val HOLO_GLASS_BORDER: Int = 0x5900E5FF
    /** Demo Holo tab-bar hairline: rgba(0,229,255,.18). */
    @ColorInt const val HOLO_TAB_BORDER: Int = 0x2E00E5FF

    private fun isHolo(context: Context): Boolean =
        context.attrColor(R.attr.shadowColor) == 0x5900E5FF.toInt()

    fun hideShadows(context: Context): Boolean =
        context.attrBoolean(R.attr.hideShadows, false)

    /** Coloured outline shadow + elevation. No-op colour on API < 28. */
    fun applyShadow(view: View, elevationDp: Float, @ColorInt color: Int) {
        view.elevation = elevationDp * view.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineSpotShadowColor = color
            view.outlineAmbientShadowColor = color
        }
    }

    /**
     * Card glow (demo §2.5: card `0 4px 14px var(--shadowc)`). Raises the
     * card to 4dp with the theme's `shadowColor`. Holo cards become glass:
     * translucent background + cyan border (§2.5). Zen is untouched.
     */
    fun glowCard(card: MaterialCardView, context: Context) {
        if (hideShadows(context)) return
        applyShadow(card, 4f, context.attrColor(R.attr.shadowColor))
        if (isHolo(context)) {
            card.setCardBackgroundColor(HOLO_GLASS_BG)
            card.strokeColor = HOLO_GLASS_BORDER
            card.strokeWidth =
                (1f * context.resources.displayMetrics.density).toInt()
        }
    }

    /** Primary-button glow (demo §2.4). Skipped for Zen's outline buttons. */
    fun glowPrimaryButton(button: View, context: Context) {
        if (hideShadows(context) || context.attrBoolean(R.attr.outlineButtons, false)) return
        applyShadow(
            button, 6f,
            if (isHolo(context)) GLOW_CYAN else GLOW_GREEN_BUTTON
        )
    }

    /** Centre FAB glow (demo §2.4: hardcoded green, cyan in Holo). */
    fun glowFab(fab: View, context: Context) {
        if (hideShadows(context)) {
            fab.elevation = 0f
            return
        }
        applyShadow(fab, 8f, if (isHolo(context)) GLOW_CYAN_FAB else GLOW_GREEN_FAB)
    }

    /** Avatar glow (demo §2.4: green, Dawn warm + white ring, Zen none). */
    fun glowAvatar(avatar: View, context: Context) {
        if (hideShadows(context)) return
        val color = when (ThemeManager.current(context)) {
            ShieldTheme.DAWN -> GLOW_WARM_AVATAR
            ShieldTheme.HOLO -> GLOW_CYAN_FAB
            else -> GLOW_GREEN_FAB
        }
        applyShadow(avatar, 6f, color)
    }

    /** Shield tile glow (demo §2.3: 0 8px 20px var(--shadowc)). */
    fun glowShieldTile(tile: View, context: Context) {
        if (hideShadows(context)) return
        applyShadow(tile, 8f, context.attrColor(R.attr.shadowColor))
    }

    /** Selected preset chip / day pill glow (demo §2.5: green .28). */
    fun glowChipSelected(chip: View, context: Context) {
        if (hideShadows(context)) return
        applyShadow(
            chip, 6f,
            if (isHolo(context)) GLOW_CYAN else GLOW_GREEN_CHIP
        )
    }

    /** Deselected chip / pill: no elevation, no glow. */
    fun glowChipDeselected(chip: View) {
        chip.elevation = 0f
    }

    /**
     * Bottom-nav glow (demo §2.5: tab bar `0 14px 34px rgba(20,60,44,.20)`).
     * Zen's bar stays flat and transparent.
     */
    fun glowBottomNav(navBar: MaterialCardView, fab: View, context: Context) {
        if (hideShadows(context)) {
            navBar.cardElevation = 0f
            fab.elevation = 0f
            return
        }
        applyShadow(navBar, 6f, context.attrColor(R.attr.shadowColor))
        glowFab(fab, context)
        if (isHolo(context)) {
            navBar.strokeColor = HOLO_TAB_BORDER
            navBar.strokeWidth =
                (1f * context.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Zen page side padding (demo §4: Zen pages use 26px side padding vs
     * 20px everywhere else). Finds the screen's ScrollView content and
     * widens only its start/end padding.
     */
    fun applyZenPagePadding(activity: AppCompatActivity) {
        if (ThemeManager.current(activity) != ShieldTheme.ZEN) return
        val scroll = findScrollView(activity.window.decorView) ?: return
        val content = scroll.getChildAt(0) ?: return
        val pad = (26f * activity.resources.displayMetrics.density).toInt()
        content.setPadding(pad, content.paddingTop, pad, content.paddingBottom)
    }

    private fun findScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findScrollView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Pause the screen's scenery (host activity's onPause). */
    fun pauseScenery(activity: AppCompatActivity) {
        activity.findViewById<SceneryView>(R.id.sceneryView)?.pauseAnimations()
    }

    /** Resume the screen's scenery (host activity's onResume). */
    fun resumeScenery(activity: AppCompatActivity) {
        activity.findViewById<SceneryView>(R.id.sceneryView)?.resumeAnimations()
    }
}
