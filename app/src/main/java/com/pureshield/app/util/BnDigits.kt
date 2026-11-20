package com.pureshield.app.util

/**
 * Bengali digits. Western digits 0-9 map to ০-৯; everything else (colons, spaces, unit
 * words) passes through unchanged, so formatted times stay readable in the Bangla UI.
 */
object BnDigits {

    private val DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

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
