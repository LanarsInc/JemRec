package com.jemcik.jemrec.crm

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Dates and times of a next call are Oslo's, whatever the phone's time zone
 * (§1): a seller abroad who plans "tomorrow 10:00" means the CRM's tomorrow,
 * where the deal's people are. And `next.due_date` may not be before the
 * call's own Oslo date (§6), which near midnight is not the phone's date.
 */
object OsloTime {

    val ZONE: ZoneId = ZoneId.of("Europe/Oslo")

    private val TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun dateOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate()

    /** The earliest date a next call after this call may have. */
    fun earliestNextDate(callStartMs: Long): LocalDate = dateOf(callStartMs)

    fun isValidNextDate(date: LocalDate, callStartMs: Long): Boolean = !date.isBefore(earliestNextDate(callStartMs))

    /** `HH:MM`, 00:00 to 23:59, as §6 takes `due_time`. */
    fun parseTime(text: String): LocalTime? = try {
        if (text.length != 5) null else LocalTime.parse(text, TIME)
    } catch (_: DateTimeParseException) {
        null
    }

    fun formatTime(time: LocalTime): String = time.format(TIME)
}
