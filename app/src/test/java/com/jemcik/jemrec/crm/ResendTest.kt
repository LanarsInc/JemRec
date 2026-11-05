package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.api.Bodies
import com.jemcik.jemrec.crm.api.Content as Texts
import com.jemcik.jemrec.crm.db.WorkCallEntry
import com.jemcik.jemrec.ui.CallSheetUi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Send the transcript again (LANARS, 2026-11-05: "revision: 1"). What the stand answered that
 * day: a higher revision replaces the texts, the same one with other texts is 409, 0 to 3 only,
 * and only with ready.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class ResendTest {

    private fun call(content: Content, revision: Int = 0) = WorkCallEntry(
        clientCallId = "c", callLogId = 1, userId = 1, outgoing = false, answered = true, phone = "1", presentation = "ALLOWED",
        countryIso = null, startedAtMs = 0, durationSeconds = 274, endedAtMs = 1, recording = "r.ogg", outcome = "REACHED",
        localState = "SENT", dueAtMs = 0, contentState = content.name, serverId = 1, contentRevision = revision,
    )

    private val texts = Texts("Summary.", "Seller: Hei.", "nb", false)

    @Test fun sentTextsCanBeSentAgainThreeTimes() {
        assertTrue(WorkCallMachine.canResend(call(Content.SENT_READY), 0))
        assertTrue(WorkCallMachine.canResend(call(Content.SENT_READY, 2), 0))
        assertFalse(WorkCallMachine.canResend(call(Content.SENT_READY, 3), 0))
        assertEquals(1, WorkCallMachine.resendsLeft(call(Content.SENT_READY, 2)))
    }

    @Test fun resendingTakesTheNextRevisionAndOnlyFromSent() {
        val asked = WorkCallMachine.resend(call(Content.SENT_READY, 1))
        assertEquals(Content.RESENDING.name, asked.contentState)
        assertEquals(2, asked.contentRevision)
        // A call whose first texts never went sends them as before, with no revision.
        val failed = call(Content.SENT_FAILED)
        assertTrue(WorkCallMachine.resend(failed) === failed)
    }

    @Test fun textsMadeAgainGoWithTheirRevision() {
        val ready = ContentHook.ready(call(Content.RESENDING, 1), Bodies.ready(texts, 1))!!
        assertEquals(Content.READY_TO_SEND.name, ready.contentState)
        assertEquals(1, JSONObject(ready.contentBody!!).getInt("revision"))
        assertFalse(JSONObject(Bodies.ready(texts)).has("revision"))
    }

    @Test fun textsThatCannotBeMadeAgainLeaveTheServersAndTheRevision() {
        val back = ContentHook.failed(call(Content.RESENDING, 2))!!
        assertEquals(Content.SENT_READY.name, back.contentState)
        assertEquals(1, back.contentRevision)
        assertEquals("transcript sent; it could not be made again", CallSheetUi.contentText(back))
    }

    @Test fun eachMomentIsSaid() {
        assertEquals("making the transcript again; the Sales Engine keeps the one sent", CallSheetUi.contentText(call(Content.RESENDING, 1)))
        assertEquals("sending the transcript again", CallSheetUi.contentText(call(Content.READY_TO_SEND, 1)))
        assertEquals("transcript sent again", CallSheetUi.contentText(call(Content.SENT_READY, 1)))
        assertNull(CallSheetUi.contentText(call(Content.NOT_EXPECTED)))
    }
}
