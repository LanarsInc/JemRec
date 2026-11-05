package com.jemcik.jemrec.crm

import android.provider.CallLog
import com.jemcik.jemrec.crm.api.Outcome
import com.jemcik.jemrec.crm.api.Presentation

/**
 * A call-log row as the Sales Engine wants the call (§6, research R5). Pure:
 * the constants are compile-time ints, so this runs in a JVM test.
 */
object CallLogRows {

    /** What the call log says of one call. */
    data class Row(
        val id: Long,
        val dateMs: Long,
        val durationSeconds: Int,
        val type: Int,
        val number: String?,
        val presentation: Int,
        val component: String?,
        val accountId: String?,
    )

    data class Facts(
        val outgoing: Boolean,
        val answered: Boolean,
        val phone: String?,
        val presentation: Presentation,
        val startedAtMs: Long,
        val durationSeconds: Int,
        val endedAtMs: Long,
        val outcome: Outcome,
    )

    /**
     * The call, or null for a row that is not sent: a blocked number (the
     * seller blocked it), or a call another device answered.
     *
     * An outgoing call is answered when Android counted talk time; incoming
     * rows other than INCOMING - missed, rejected, sent to voicemail - are
     * calls the seller did not take. Vendors' own types for an answered
     * call are taken as answered, as CallLogLookup does.
     */
    fun facts(row: Row): Facts? {
        val (outgoing, answered) = when (row.type) {
            CallLog.Calls.BLOCKED_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> return null
            CallLog.Calls.OUTGOING_TYPE -> true to (row.durationSeconds > 0)
            CallLog.Calls.INCOMING_TYPE -> false to true
            CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> false to false
            else -> false to (row.durationSeconds > 0)
        }
        val presentation = when (row.presentation) {
            CallLog.Calls.PRESENTATION_ALLOWED -> Presentation.ALLOWED
            CallLog.Calls.PRESENTATION_RESTRICTED -> Presentation.RESTRICTED
            CallLog.Calls.PRESENTATION_PAYPHONE -> Presentation.PAYPHONE
            else -> Presentation.UNKNOWN // §6: any other Android value is unknown
        }
        val number = row.number?.trim()?.takeIf { it.isNotEmpty() }
        // A row that says "allowed" with no number has nothing to show: unknown.
        val shown = if (presentation == Presentation.ALLOWED && number == null) Presentation.UNKNOWN else presentation
        val seconds = if (answered) row.durationSeconds.coerceIn(0, 86_400) else 0
        return Facts(
            outgoing = outgoing,
            answered = answered,
            phone = if (shown == Presentation.ALLOWED) number else null,
            presentation = shown,
            startedAtMs = row.dateMs,
            durationSeconds = seconds,
            endedAtMs = row.dateMs + row.durationSeconds * 1000L,
            outcome = if (answered) Outcome.REACHED else Outcome.NO_ANSWER,
        )
    }
}
