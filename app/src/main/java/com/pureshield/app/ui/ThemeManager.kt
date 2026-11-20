package com.pureshield.app.ui

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pureshield.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * PureShield theme system — 5 full UI themes.
 *
 * Each [ShieldTheme] is a complete look (colours + corner radii + fonts +
 * layout flags + decor), matching the PureLock web demo. Themes are applied
 * with [ThemeManager.apply], which MUST be called BEFORE super.onCreate()
 * in every Activity:
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         ThemeManager.apply(this)
 *         super.onCreate(savedInstanceState)
 *         ...
 *     }
 *
 * Switching theme: call [ThemeManager.set] (or [ThemeManager.cycle]) and
 * then activity.recreate(). The id is persisted in DataStore
 * ("pureshield_theme", key "theme_id", default "original") and also kept in a
 * static volatile cache so recreate() picks the new theme up immediately.
 *
 * Font mapping (demo web font -> closest Android SYSTEM family; Google
 * Fonts files are binaries and cannot be pushed via this project's push
 * tool, so this mapping is the sanctioned adaptation):
 *   Sora -> sans-serif | Lora -> serif
 *   Zilla Slab -> serif | Manrope -> sans-serif | Jost -> sans-serif-light
 *   Orbitron -> monospace
 */
private val Context.themeDataStore by preferencesDataStore(name = "pureshield_theme")

enum class ShieldTheme(
    val id: String,
    val displayNameBn: String,
    val displayNameEn: String,
    val descriptionBn: String,
    val styleRes: Int,
    /** Picker dots: background / mint / accent (exact demo tokens). */
    val dotColors: IntArray,
    /** Quick Actions grid columns for this theme (Ocean uses 2 wide columns). */
    val quickActionColumns: Int,
    /** Stats grid columns for this theme (Zen collapses to 1 column). */
    val statsColumns: Int
) {
    ORIGINAL(
        id = "original",
        displayNameBn = "অরিজিনাল ডার্ক",
        displayNameEn = "Original Dark",
        descriptionBn = "সাইবার-শার্প ডার্ক লুক — সবুজ গ্রিড আর ঝলমলে মিন্ট আলো",
        styleRes = R.style.Theme_PureShield_Original,
        dotColors = intArrayOf(0xFF0A0F0D.toInt(), 0xFF17C98B.toInt(), 0xFF2DD4BF.toInt()),
        quickActionColumns = 4,
        statsColumns = 2
    ),
    JUNGLE(
        id = "jungle",
        displayNameBn = "জঙ্গল সবুজ",
        displayNameEn = "Jungle Green",
        descriptionBn = "জঙ্গল সবুজ পাতার আকৃতি — প্রকৃতির ছোঁয়ায় গভীর ফোকাস",
        styleRes = R.style.Theme_PureShield_Jungle,
        dotColors = intArrayOf(0xFFE9F4E1.toInt(), 0xFF2E7D32.toInt(), 0xFF81C784.toInt()),
        quickActionColumns = 4,
        statsColumns = 2
    ),
    OCEAN(
        id = "ocean",
        displayNameBn = "সমুদ্র নীল",
        displayNameEn = "Ocean Blue",
        descriptionBn = "সমুদ্র নীল ঢেউ আর বুদবুদ — প্রশান্ত গভীরতার অনুভূতি",
        styleRes = R.style.Theme_PureShield_Ocean,
        dotColors = intArrayOf(0xFFE4F5FB.toInt(), 0xFF0288D1.toInt(), 0xFF4DD0E1.toInt()),
        quickActionColumns = 2,
        statsColumns = 2
    ),
    ZEN(
        id = "zen",
        displayNameBn = "রিল্যাক্স জেন",
        displayNameEn = "Relax Zen",
        descriptionBn = "সাদামাটা মিনিমাল জেন — বাড়তি কিছু নেই, শুধু শান্তি",
        styleRes = R.style.Theme_PureShield_Zen,
        dotColors = intArrayOf(0xFFF2F0FA.toInt(), 0xFF7C6BB0.toInt(), 0xFFB8B0E8.toInt()),
        quickActionColumns = 4,
        statsColumns = 1
    ),
    HOLO(
        id = "holo",
        displayNameBn = "হোলোগ্রাম",
        displayNameEn = "Hologram",
        descriptionBn = "সায়ান-ম্যাজেন্টা হোলোগ্রাম — ভবিষ্যতের আলোয় ঝলমলে",
        styleRes = R.style.Theme_PureShield_Holo,
        dotColors = intArrayOf(0xFF0B1026.toInt(), 0xFF00E5FF.toInt(), 0xFFE040FB.toInt()),
        quickActionColumns = 4,
        statsColumns = 2
    );

    companion object {
        /** Cycle order for the dashboard half-moon button + Settings picker. */
        val ORDER: List<ShieldTheme> =
            listOf(ORIGINAL, JUNGLE, OCEAN, ZEN, HOLO)
    }
}

object ThemeManager {

    private val THEME_ID_KEY = stringPreferencesKey("theme_id")
    private const val DEFAULT_THEME_ID = "original"

    /** In-memory cache so recreate() picks a just-saved theme up immediately. */
    @Volatile
    private var cachedId: String? = null

    /**
     * Apply the persisted theme to [activity]. MUST be called before
     * super.onCreate() — setTheme() after that has no effect.
     */
    fun apply(activity: Activity) {
        activity.setTheme(current(activity).styleRes)
    }

    /** The currently persisted theme (default [ShieldTheme.ORIGINAL]). */
    fun current(context: Context): ShieldTheme {
        val id = cachedId ?: runBlocking(Dispatchers.IO) {
            context.themeDataStore.data
                .map { prefs -> prefs[THEME_ID_KEY] ?: DEFAULT_THEME_ID }
                .first()
        }.also { cachedId = it }
        return themeById(id)
    }

    /** Persist [theme] (synchronously enough for an immediate recreate()). */
    fun set(context: Context, theme: ShieldTheme) {
        cachedId = theme.id
        runBlocking(Dispatchers.IO) {
            context.themeDataStore.edit { prefs -> prefs[THEME_ID_KEY] = theme.id }
        }
    }

    /**
     * Advance to the next theme in cycle order
     * (original -> jungle -> ocean -> zen -> holo -> original),
     * persist it and return it. Caller should recreate().
     */
    fun cycle(context: Context): ShieldTheme {
        val order = ShieldTheme.ORDER
        val index = order.indexOf(current(context))
        val next = order[(index + 1) % order.size]
        set(context, next)
        return next
    }

    /** All themes in cycle order. */
    fun all(): List<ShieldTheme> = ShieldTheme.ORDER

    /** Theme for [id], or [ShieldTheme.ORIGINAL] for an unknown id. */
    fun themeById(id: String?): ShieldTheme {
        if (id == null) return ShieldTheme.ORIGINAL
        return ShieldTheme.ORDER.firstOrNull { it.id == id } ?: ShieldTheme.ORIGINAL
    }
}
