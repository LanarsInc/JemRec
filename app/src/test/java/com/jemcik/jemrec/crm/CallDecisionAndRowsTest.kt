package com.jemcik.jemrec.crm

import android.provider.CallLog
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.DealChoice
import com.jemcik.jemrec.crm.api.Outcome
import com.jemcik.jemrec.crm.api.Presentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** §5.1's table, and a call-log row to the call step 1 sends. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallDecisionAndRowsTest {

    private fun lookup(name: String) = ApiModels.lookup(CrmFixtures.data(name))

    @Test
    fun `one open deal goes there by itself, chosen by auto`() {
        val l = lookup("lookup-deal")
        assertEquals(CallDecision.Shown.OneDeal(1000456, "Eksempel AS – nettside", "Eksempel AS"), CallDecision.shown(l))
        assertEquals(CallDecision.Default(DealChoice.DEAL, 1000456, false), CallDecision.default(l))
    }

    @Test
    fun `several open deals are never logged without a tap - auto, and the server holds it`() {
        val data = CrmFixtures.data("lookup-deal").put("auto_deal_id", org.json.JSONObject.NULL)
        val l = ApiModels.lookup(data)
        assertEquals(CallDecision.Shown.Choose(2), CallDecision.shown(l))
        assertEquals(CallDecision.Default(DealChoice.AUTO, null, null), CallDecision.default(l))
    }

    @Test
    fun `a colleague, no match, and no lookup at all`() {
        assertEquals(CallDecision.Shown.Colleague, CallDecision.shown(lookup("lookup-internal")))
        assertEquals(CallDecision.Shown.ServerDecides, CallDecision.shown(lookup("lookup-none")))
        assertEquals(CallDecision.Shown.ServerDecides, CallDecision.shown(null))
        assertEquals(DealChoice.AUTO, CallDecision.default(null).choice)
    }

    private fun row(type: Int, seconds: Int = 0, number: String? = "+47 400 00 001", presentation: Int = CallLog.Calls.PRESENTATION_ALLOWED) =
        CallLogRows.Row(1, 1_000_000, seconds, type, number, presentation, "com.android.phone/x", "1")

    @Test
    fun `each kind of row`() {
        val out = CallLogRows.facts(row(CallLog.Calls.OUTGOING_TYPE, 184))!!
        assertTrue(out.outgoing && out.answered)
        assertEquals(184, out.durationSeconds)
        assertEquals(1_184_000, out.endedAtMs)
        assertEquals(Outcome.REACHED, out.outcome)

        val unanswered = CallLogRows.facts(row(CallLog.Calls.OUTGOING_TYPE, 0))!!
        assertEquals(false, unanswered.answered)
        assertEquals(Outcome.NO_ANSWER, unanswered.outcome)

        for (t in listOf(CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.VOICEMAIL_TYPE)) {
            val missed = CallLogRows.facts(row(t, 12))!!
            assertEquals(false, missed.outgoing)
            assertEquals(false, missed.answered)
            assertEquals("a missed call lasts nothing", 0, missed.durationSeconds)
        }
        assertNull(CallLogRows.facts(row(CallLog.Calls.BLOCKED_TYPE)))
        assertNull(CallLogRows.facts(row(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE)))
    }

    @Test
    fun `a hidden number is sent as nothing`() {
        val hidden = CallLogRows.facts(row(CallLog.Calls.INCOMING_TYPE, 95, number = "", presentation = CallLog.Calls.PRESENTATION_RESTRICTED))!!
        assertNull(hidden.phone)
        assertEquals(Presentation.RESTRICTED, hidden.presentation)
        val noNumber = CallLogRows.facts(row(CallLog.Calls.INCOMING_TYPE, 95, number = null))!!
        assertEquals(Presentation.UNKNOWN, noNumber.presentation)
        assertEquals(Presentation.UNKNOWN, CallLogRows.facts(row(CallLog.Calls.INCOMING_TYPE, 1, presentation = 99))!!.presentation)
    }
}
