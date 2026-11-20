package com.pureshield.app.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class FormatAndDayTest {

    @Before
    fun fixLocale() {
        Locale.setDefault(Locale.US) // so digits and decimal separators are predictable
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // ------------------------------------------------------------------ DurationFormat

    @Test
    fun `clock formatting`() {
        assertEquals("1d 02:03:04", DurationFormat.clock(93_784_000))
        assertEquals("01:02:05", DurationFormat.clock(3_725_000))
        assertEquals("00:59", DurationFormat.clock(59_000))
        assertEquals("00:00", DurationFormat.clock(0))
        assertEquals("00:00", DurationFormat.clock(-5))
        assertEquals("00:01", DurationFormat.clock(1)) // never shows 00:00 before the real end
    }

    @Test
    fun `compact formatting`() {
        assertEquals("3h 20m", DurationFormat.compact(12_000_000))
        assertEquals("45m", DurationFormat.compact(45 * 60_000L))
        assertEquals("2d 4h", DurationFormat.compact((2 * 1_440 + 240) * 60_000L))
        assertEquals("0m", DurationFormat.compact(-1))
    }

    @Test
    fun `distance formatting`() {
        assertEquals("500 m", DurationFormat.distance(500f))
        assertEquals("999 m", DurationFormat.distance(999.9f))
        assertEquals("1.50 km", DurationFormat.distance(1_500f))
    }

    @Test
    fun `hrs and mins formatting`() {
        assertEquals("03 hrs 05 mins", DurationFormat.formatHrsMins(3 * 3_600_000L + 5 * 60_000L))
        assertEquals("41 mins 11 sec", DurationFormat.formatMinsSecs(41 * 60_000L + 11_000L))
        assertEquals("03 sec", DurationFormat.formatMinsSecs(3_000L))
    }

    // ------------------------------------------------------------------ DayUtils

    @Test
    fun `date key follows the given time zone`() {
        val t = ms("2026-11-19T20:00:00Z")
        assertEquals("2026-11-19", DayUtils.dateKey(t, ZoneId.of("UTC")))
        assertEquals("2026-11-20", DayUtils.dateKey(t, ZoneId.of("Asia/Dhaka")))
    }

    @Test
    fun `split by day credits each day its share`() {
        // 2026-11-19 23:30 UTC -> 2026-11-20 00:30 UTC: 30 min each side.
        val start = ms("2026-11-19T23:30:00Z")
        val end = ms("2026-11-20T00:30:00Z")
        val parts = DayUtils.splitByDay(start, end, ZoneId.of("UTC"))
        assertEquals(2, parts.size)
        assertEquals("2026-11-19" to 30 * 60_000L, parts[0])
        assertEquals("2026-11-20" to 30 * 60_000L, parts[1])
    }

    // ------------------------------------------------------------------ BnDigits

    @Test
    fun `bengali digits`() {
        assertEquals("১২৩৪৫৬৭৮৯০", BnDigits.toBnDigits("1234567890"))
        assertEquals("১২:৩০", BnDigits.toBnDigits("12:30"))
        assertEquals("abc", BnDigits.toBnDigits("abc"))
    }

    @Test
    fun `bengali minutes`() {
        assertEquals("৪৫ মিনিট", BnDigits.formatMinutes(45))
        assertEquals("১ ঘণ্টা ৩০ মিনিট", BnDigits.formatMinutes(90))
        assertEquals("০ মিনিট", BnDigits.formatMinutes(0))
    }

    @Test
    fun `bengali hms`() {
        assertEquals("০২:০৫", BnDigits.formatHMS(125_000))
        assertEquals("০১:০২:০৫", BnDigits.formatHMS(3_725_000))
    }

    @Test
    fun `bengali clock hhmm`() {
        assertEquals("০১:৩০", BnDigits.formatClockHHMM(90 * 60_000L))
        assertEquals("০০:০৫", BnDigits.formatClockHHMM(5 * 60_000L))
    }

    // ------------------------------------------------------- Localized digits

    @Test
    fun `localized digits follow the UI language`() {
        // Locale.US (set in fixLocale): English UI -> Western digits/units.
        assertEquals("12:30", BnDigits.toLocalizedDigits("12:30"))
        assertEquals("02:05", BnDigits.formatHMSLocalized(125_000))
        assertEquals("01:02:05", BnDigits.formatHMSLocalized(3_725_000))
        assertEquals("45 minutes", BnDigits.formatMinutesLocalized(45))
        assertEquals("1 hour 30 minutes", BnDigits.formatMinutesLocalized(90))
        assertEquals("1 hour", BnDigits.formatMinutesLocalized(60))
        assertEquals("0 min", BnDigits.formatMinutesShort(0))
        assertEquals("45 min", BnDigits.formatMinutesShort(45))
        assertEquals("2 hr 10 min", BnDigits.formatMinutesShort(130))
        assertEquals("2 hr", BnDigits.formatMinutesShort(120))

        // Bengali device locale: same functions produce Bengali digits/units.
        Locale.setDefault(Locale("bn"))
        try {
            assertEquals("১২:৩০", BnDigits.toLocalizedDigits("12:30"))
            assertEquals("০২:০৫", BnDigits.formatHMSLocalized(125_000))
            assertEquals("৪৫ মিনিট", BnDigits.formatMinutesLocalized(45))
            assertEquals("১ ঘণ্টা ৩০ মিনিট", BnDigits.formatMinutesLocalized(90))
            assertEquals("০ মি", BnDigits.formatMinutesShort(0))
            assertEquals("২ ঘ ১০ মি", BnDigits.formatMinutesShort(130))
        } finally {
            Locale.setDefault(Locale.US)
        }
    }
}
