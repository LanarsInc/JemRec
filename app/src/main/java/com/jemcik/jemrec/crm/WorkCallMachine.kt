package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.db.WorkCallEntry

/**
 * A work call's states, and the one thing to do next for it (data-model.md).
 * Pure: the queue (CrmSync) asks this, does what it says, and stores the
 * result.
 */
object WorkCallMachine {

    enum class Local {
        /** Taken from the call log; its number not looked up yet. */
        NEW,
        /** Looked up (or not to be); held until dueAtMs, for the seller to change the deal. */
        WAITING,
        /** A colleague's number: nothing more is sent (§5). */
        INTERNAL,
        /** Step 1 went; the server's state is in serverJson. */
        SENT,
        /** It will not be sent: too old, refused, or gone from the server. Shown on its row. */
        NOT_SENT,
        ;

        companion object {
            // "PRIVATE" (the API of 2026-11-02's Private, dropped 2026-11-03) is
            // no longer a state: a debug install's row with it reads as not sent.
            fun of(name: String): Local = entries.firstOrNull { it.name == name } ?: NOT_SENT
        }
    }

    enum class Content {
        /** No recording: step 1 said no transcript follows. */
        NOT_EXPECTED,
        /** A recording; its transcript and summary are not made yet. */
        EXPECTED,
        /** The texts are in contentBody, to send. */
        READY_TO_SEND,
        /** "failed" is to be sent (FR-031). */
        FAILED_TO_SEND,
        SENT_READY,
        /** "failed" went; a first success may still follow, once (§8). */
        SENT_FAILED,
        /** The server refused it, or the call never reached the server: nothing more. */
        DONE,
        ;

        val waitsForServerId: Boolean get() = this == READY_TO_SEND || this == FAILED_TO_SEND

        companion object {
            fun of(name: String): Content = entries.firstOrNull { it.name == name } ?: DONE
        }
    }

    enum class Action { LOOKUP, STEP_ONE, PICK, CONTENT, FAIL_CONTENT }

    /** What to do for [c] and from when; null when there is nothing left to do for it. */
    fun next(c: WorkCallEntry): Pair<Action, Long>? {
        val local = Local.of(c.localState)
        val content = Content.of(c.contentState)
        return when (local) {
            Local.NEW -> Action.LOOKUP to c.dueAtMs
            Local.WAITING -> Action.STEP_ONE to c.dueAtMs
            Local.INTERNAL, Local.NOT_SENT -> null
            Local.SENT -> when {
                c.serverId == null -> null
                c.pickChoice != null -> Action.PICK to c.dueAtMs
                content.waitsForServerId -> Action.CONTENT to maxOf(c.dueAtMs, c.contentDueAtMs)
                // Waiting for the phone's transcript; failed is sent if it has not come by then.
                content == Content.EXPECTED -> Action.FAIL_CONTENT to deadline(c)
                else -> null
            }
        }
    }

    /** §8: "If content is not ready 72 hours after the call, send failed." */
    fun deadline(c: WorkCallEntry): Long = c.startedAtMs + CONTENT_DEADLINE_MS

    /** §6: `started_at` at most 30 days old. A little less, so the request is not refused on the way. */
    fun tooOld(c: WorkCallEntry, nowMs: Long): Boolean = nowMs - c.startedAtMs > MAX_AGE_MS

    /** 1, 2, 4 ... 60 minutes (research R6). */
    fun backoffMs(attempts: Int): Long = (60_000L shl attempts.coerceIn(0, 6)).coerceAtMost(60 * 60_000L)

    /**
     * How long a call is held after it ends before step 1 goes, unless the
     * seller confirms sooner: their chance to change the deal, outcome or next
     * call (FR-020). It was the window for Private until the API of 2026-11-03
     * dropped Private ("it is a work phone"); kept at 2 minutes (2026-11-04).
     */
    const val HOLD_MS = 2 * 60_000L
    const val CONTENT_DEADLINE_MS = 72L * 60 * 60_000
    const val MAX_AGE_MS = 30L * 24 * 60 * 60_000 - 60 * 60_000
}
