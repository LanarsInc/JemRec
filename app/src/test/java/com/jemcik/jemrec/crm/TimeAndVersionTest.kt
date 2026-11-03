package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.api.Instants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class TimeAndVersionTest {

    @Test
    fun `instants as the server writes them, milliseconds always`() {
        assertEquals("2026-11-03T08:14:05.123Z", Instants.format(Instants.parse("2026-11-03T08:14:05.123Z")))
        assertEquals("2026-11-03T08:05:00.000Z", Instants.format(Instants.parse("2026-11-03T08:05:00Z")))
    }

    @Test
    fun `a next call's date is Oslo's, whatever the phone's zone`() {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // 23:30 UTC on 3 Nov is 00:30 on 4 Nov in Oslo (CET, UTC+1).
            val start = Instants.parse("2026-11-03T23:30:00.000Z")
            assertEquals(LocalDate.of(2026, 11, 4), OsloTime.dateOf(start))
            assertFalse(OsloTime.isValidNextDate(LocalDate.of(2026, 11, 3), start))
            assertTrue(OsloTime.isValidNextDate(LocalDate.of(2026, 11, 4), start))
            // Summer time: 22:30 UTC on 30 June is 00:30 on 1 July (CEST, UTC+2).
            assertEquals(LocalDate.of(2026, 7, 1), OsloTime.dateOf(Instants.parse("2026-06-30T22:30:00.000Z")))
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test
    fun `times are HH colon MM`() {
        assertEquals("10:00", OsloTime.parseTime("10:00")?.let(OsloTime::formatTime))
        assertNull(OsloTime.parseTime("9:00"))
        assertNull(OsloTime.parseTime("24:00"))
        assertNull(OsloTime.parseTime("10:00:00"))
    }

    @Test
    fun `versions compare as dotted numbers, suffixes ignored`() {
        assertTrue(AppVersion.isBelow("0.26.0", "1.4.0"))
        assertFalse(AppVersion.isBelow("1.4.0-debug", "1.4.0"))
        assertFalse(AppVersion.isBelow("1.10.0", "1.9.9"))
        assertFalse(AppVersion.isBelow("1.0", "1.0.0"))
        assertFalse(AppVersion.isBelow("1.0.0", null))
    }
}
