package com.jemcik.jemrec.ui

import com.jemcik.jemrec.crm.WorkCallMachine
import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.db.WorkCallEntry

/**
 * The work calls' queue in Settings > Sales Engine: what has not gone yet, and why. Before it a
 * seller could see a call's state only call by call (asked on the A24, 2026-11-04).
 */
data class SalesEngineQueue(
    /** Calls, or their transcripts, still to go. */
    val waiting: Int,
    /** Of those, waiting out a failed try: Retry now can hurry them. */
    val retrying: Int,
    /** Why the first of those waits, in plain words (CallSheetUi.retryText). */
    val why: String?,
    /** Calls the queue gave up on. */
    val notSent: Int,
    /** Transcripts that went as "failed" and could still be sent (WorkCallMachine.canResend). */
    val transcriptsNotSent: Int,
) {
    val summary: String
        get() {
            val parts = listOfNotNull(
                waiting.takeIf { it > 0 }?.let { "$it waiting to be sent" },
                notSent.takeIf { it > 0 }?.let { "$it not sent" },
                transcriptsNotSent.takeIf { it > 0 }?.let { if (it == 1) "1 transcript not sent" else "$it transcripts not sent" },
            )
            val line = if (parts.isEmpty()) "All sent" else parts.joinToString(" · ")
            return why?.let { "$line\n${it.replaceFirstChar(Char::uppercase)}" } ?: line
        }

    companion object {
        /** Pure: null when there are no work calls at all. */
        fun of(calls: List<WorkCallEntry>, nowMs: Long, time: (Long) -> String = CallSheetUi::clockTime): SalesEngineQueue? {
            if (calls.isEmpty()) return null
            val toGo = calls.filter { c ->
                when (Local.of(c.localState)) {
                    Local.NEW, Local.WAITING -> true
                    Local.SENT -> Content.of(c.contentState).waitsForServerId || c.pickChoice != null
                    else -> false
                }
            }
            val retrying = toGo.filter { it.attempts > 0 || it.lastError == "rate limited" }
            return SalesEngineQueue(
                waiting = toGo.size,
                retrying = retrying.count { WorkCallMachine.retryNow(it, nowMs) != null },
                why = retrying.minByOrNull { it.dueAtMs }?.let { CallSheetUi.retryText(it.lastError, it.dueAtMs, time) },
                notSent = calls.count { Local.of(it.localState) == Local.NOT_SENT },
                // Not sent, and could be: not those that went and could only be sent again.
                transcriptsNotSent = calls.count {
                    WorkCallMachine.canResend(it, nowMs) &&
                        Content.of(it.contentState).let { s -> s == Content.FAILED_TO_SEND || s == Content.SENT_FAILED }
                },
            )
        }
    }
}
