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
        /** Looked up (or not to be); the Private window is open until dueAtMs. */
        WAITING,
        /** The seller tapped Private before step 1: nothing more is sent (§13). */
        PRIVATE,
        /** A colleague's number: nothing more is sent (§5). */
        INTERNAL,
        /** Step 1 went; the server's state is in serverJson. */
        SENT,
        /** It will not be sent: too old, refused, or gone from the server. Shown on its row. */
        NOT_SENT,
        ;

        companion object {
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

    enum class Action { LOOKUP, STEP_ONE, PICK, MAKE_PRIVATE, CONTENT, FAIL_CONTENT }

    /** What to do for [c] and from when; null when there is nothing left to do for it. */
    fun next(c: WorkCallEntry): Pair<Action, Long>? {
        val local = Local.of(c.localState)
        val content = Content.of(c.contentState)
        return when (local) {
            Local.NEW -> Action.LOOKUP to c.dueAtMs
            Local.WAITING -> Action.STEP_ONE to c.dueAtMs
            Local.PRIVATE, Local.INTERNAL, Local.NOT_SENT -> null
            Local.SENT -> when {
                c.serverId == null -> null
                c.privateAsked -> Action.MAKE_PRIVATE to c.dueAtMs
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

    /** The seller's chance to tap Private before step 1 (FR-020, decided 2026-11-03). */
    const val PRIVATE_WINDOW_MS = 2 * 60_000L
    const val CONTENT_DEADLINE_MS = 72L * 60 * 60_000
    const val MAX_AGE_MS = 30L * 24 * 60 * 60_000 - 60 * 60_000
}
