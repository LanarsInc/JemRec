package com.jemcik.jemrec.ui

import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.db.WorkCallEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The Sales Engine line under a work call: the call, then its transcript (asked on the A24, 2026-11-04). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CrmMarkTextTest {

    private val notInCrm = """{"call":{"id":1,"client_call_id":"c","status":"not_in_crm","content_status":"ready","candidates":[],"candidates_total":0}}"""

    private fun call(local: String, content: Content) = WorkCallEntry(
        clientCallId = "c", callLogId = 1, userId = 1, outgoing = false, answered = true, phone = "1", presentation = "ALLOWED",
        countryIso = null, startedAtMs = 0, durationSeconds = 274, endedAtMs = 1, recording = "r.ogg", outcome = "REACHED",
        localState = local, dueAtMs = 0, contentState = content.name, serverId = 1, serverJson = notInCrm,
    )

    @Test fun eachTextStateIsSaid() {
        assertEquals("Not in the CRM · transcript to follow", CallSheetUi.lineText(call("SENT", Content.EXPECTED)))
        assertEquals("Not in the CRM · sending the transcript", CallSheetUi.lineText(call("SENT", Content.READY_TO_SEND)))
        assertEquals("Not in the CRM · transcript sent", CallSheetUi.lineText(call("SENT", Content.SENT_READY)))
        assertEquals("Not in the CRM · transcript not sent", CallSheetUi.lineText(call("SENT", Content.SENT_FAILED)))
        assertEquals("Not in the CRM · transcript not sent", CallSheetUi.lineText(call("SENT", Content.FAILED_TO_SEND)))
    }

    /** §8: one real transcript may follow "failed"; not after one went, nor without a recording, nor past 30 days. */
    @Test fun sendingAgainIsOfferedOnlyAfterFailed() {
        val now = 10L * 24 * 60 * 60_000
        assertTrue(CallSheetUi.canResend(call("SENT", Content.SENT_FAILED), now))
        assertTrue(CallSheetUi.canResend(call("SENT", Content.FAILED_TO_SEND), now))
        assertFalse(CallSheetUi.canResend(call("SENT", Content.SENT_READY), now))
        assertFalse(CallSheetUi.canResend(call("SENT", Content.EXPECTED), now))
        assertFalse(CallSheetUi.canResend(call("SENT", Content.DONE), now))
        assertFalse(CallSheetUi.canResend(call("SENT", Content.SENT_FAILED).copy(recording = null), now))
        assertFalse(CallSheetUi.canResend(call("WAITING", Content.SENT_FAILED), now))
        assertFalse(CallSheetUi.canResend(call("SENT", Content.SENT_FAILED), 31L * 24 * 60 * 60_000))
    }

    @Test fun noRecordingSaysNothingOfATranscript() {
        assertEquals("Not in the CRM", CallSheetUi.lineText(call("SENT", Content.NOT_EXPECTED)))
    }

    @Test fun beforeTheCallIsSentOnlyTheCallIsSaid() {
        assertNull(CallSheetUi.contentText(call("WAITING", Content.EXPECTED)))
        assertEquals("Logging to the Sales Engine in 2 min", CallSheetUi.lineText(call("WAITING", Content.EXPECTED)))
    }
}
