package com.pureshield.app.util

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * Bengali digits. Western digits 0-9 map to ০-৯; everything else (colons, spaces, unit
 * words) passes through unchanged, so formatted times stay readable in the Bangla UI.
 *
 * The [toBnDigits] / [formatMinutes] / [formatHMS] / [formatClockHHMM] functions below
 * ALWAYS produce Bengali digits — they are the Bangla UI's formatters and their
 * behaviour is pinned by unit tests. For text that must follow the user's picked
 * language (English UI = Western digits and English units), use the *Localized
 * variants instead: they call [useBengali] and only convert digits when the app
 * is actually in Bengali mode.
 */
object BnDigits {

    private val DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    /**
     * True when the app's picked language is Bengali. Reads the locale the user
     * picked in Settings ([AppCompatDelegate.getApplicationLocales]); on a fresh
     * install (no pick yet) falls back to the device locale. Wrapped in
     * try/catch because unit tests run with stubbed AppCompat return values.
     */
    fun useBengali(): Boolean {
        return try {
            val locales = AppCompatDelegate.getApplicationLocales()
            val tag = if (!locales.isEmpty) locales[0]?.toLanguageTag() else null
            (tag ?: Locale.getDefault().toLanguageTag()).startsWith("bn")
        } catch (_: Exception) {
            Locale.getDefault().language == "bn"
        }
    }

    /** The locale the app UI is currently using (Bengali or English). */
    fun appLocale(): Locale = if (useBengali()) Locale("bn") else Locale.ENGLISH

    /** Convert Western digits to Bengali digits only in Bengali mode. */
    fun toLocalizedDigits(text: String): String =
        if (useBengali()) toBnDigits(text) else text

    /** Milliseconds -> "০১:০২:০৫" in Bengali mode, "01:02:05" in English mode. */
    fun formatHMSLocalized(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val text = if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
        return toLocalizedDigits(text)
    }

    /**
     * Minutes in words: "১ ঘণ্টা ৩০ মিনিট" / "৪৫ মিনিট" in Bengali mode,
     * "1 hour 30 minutes" / "45 minutes" in English mode.
     */
    fun formatMinutesLocalized(totalMinutes: Long): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        val text = if (useBengali()) {
            if (hours > 0) "$hours ঘণ্টা $rest মিনিট" else "$rest মিনিট"
        } else {
            val hourWord = if (hours == 1L) "hour" else "hours"
            val minWord = if (rest == 1L) "minute" else "minutes"
            when {
                hours == 0L -> "$rest $minWord"
                rest == 0L -> "$hours $hourWord"
                else -> "$hours $hourWord $rest $minWord"
            }
        }
        return toLocalizedDigits(text)
    }

    /**
     * Short minutes: "২ ঘ ১০ মি" / "৪৫ মি" / "০ মি" in Bengali mode,
     * "2 hr 10 min" / "45 min" / "0 min" in English mode.
     */
    fun formatMinutesShort(totalMinutes: Long): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        val text = if (useBengali()) {
            when {
                hours == 0L -> "$rest মি"
                rest == 0L -> "$hours ঘ"
                else -> "$hours ঘ $rest মি"
            }
        } else {
            when {
                hours == 0L -> "$rest min"
                rest == 0L -> "$hours hr"
                else -> "$hours hr $rest min"
            }
        }
        return toLocalizedDigits(text)
    }

    /** "12:30" -> "১২:৩০". */
    fun toBnDigits(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            out.append(if (c in '0'..'9') DIGITS[c - '0'] else c)
        }
        return out.toString()
    }

    /** 90 minutes -> "১ ঘণ্টা ৩০ মিনিট"; 45 minutes -> "৪৫ মিনিট"; 0 -> "০ মিনিট". */
    fun formatMinutes(totalMinutes: Long): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        val text = if (hours > 0) "$hours ঘণ্টা $rest মিনিট" else "$rest মিনিট"
        return toBnDigits(text)
    }

    /** Milliseconds -> "০১:০২:০৫" (H:MM:SS, Bengali digits); under an hour -> "০২:০৫". */
    fun formatHMS(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val text = if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
        return toBnDigits(text)
    }

    /** Milliseconds -> "০১:৩০" (HH:MM, Bengali digits, hours wrap at 24). */
    fun formatClockHHMM(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0) / 60_000
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return toBnDigits("%02d:%02d".format(hours, minutes))
    }
}
