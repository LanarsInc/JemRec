package com.jemcik.jemrec.crm.api

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Instants the way the Sales Engine writes them: UTC, ISO 8601, milliseconds,
 * `Z` (§1) - `2026-11-03T08:14:05.123Z`. `Instant.toString()` drops the
 * fraction when it is zero and writes nanoseconds when there are any, so the
 * millisecond part is put in by hand; the server compares `started_at` to the
 * whole second for idempotency (§6.3), and a body built twice must be the
 * same text.
 */
object Instants {

    fun format(epochMs: Long): String {
        val i = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.MILLIS)
        val seconds = i.truncatedTo(ChronoUnit.SECONDS).toString().removeSuffix("Z")
        return "%s.%03dZ".format(seconds, i.toEpochMilli().mod(1000L))
    }

    fun parse(text: String): Long = Instant.parse(text).toEpochMilli()
}
