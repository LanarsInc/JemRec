package com.jemcik.jemrec.summary

/**
 * One transcript line as the summary sees it: when, what, and who - "You" or
 * "Them" when the call's two sides were transcribed apart, null when not.
 * Stands where CallVault's summary code takes its database row.
 */
data class SummaryLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
)
