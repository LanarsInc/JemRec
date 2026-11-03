package com.jemcik.jemrec.transcribe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** When the overnight job starts, and which recordings it takes. */
class TranscriptionSchedulerTest {

    private val zone = TimeZone.getDefault()
    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    @After fun back() = TimeZone.setDefault(zone)

    private fun at(hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 29, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun inTheEveningItWaitsForTonight() {
        assertEquals((5 * 60 + 30) * 60_000L, TranscriptionScheduler.delayUntil(1, 30, at(20, 0)))
    }

    @Test fun pastTheHourItWaitsForTomorrowNight() {
        assertEquals((23 * 60) * 60_000L, TranscriptionScheduler.delayUntil(1, 30, at(2, 30)))
    }

    @Test fun neverLessThanAMinute() {
        assertTrue(TranscriptionScheduler.delayUntil(1, 30, at(1, 30) - 1) >= 60_000L)
    }

    @Test fun theBacklogIsUntranscribedRecentCallsNewestFirst() {
        val now = at(1, 30)
        val day = 86_400_000L
        val recordings = listOf(
            "old.ogg" to now - 40 * day,
            "done.ogg" to now - 1 * day,
            "yesterday.ogg" to now - 1 * day + 1,
            "lastweek.ogg" to now - 7 * day,
        )
        assertEquals(
            listOf("yesterday.ogg", "lastweek.ogg"),
            TranscriptionScheduler.backlog(recordings, transcribed = setOf("done.ogg"), now = now),
        )
    }
}
