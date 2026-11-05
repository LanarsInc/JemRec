package com.jemcik.jemrec.crm

import android.provider.CallLog
import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which call-log rows become work calls (FR-010, FR-011, FR-015, FR-017, FR-046). */
class CallIntakeTest {

    private val line = WorkLine.Sim(1, 0, "Work", "Telia", null, "no")
    private val phone = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"
    private val setup = 1_000_000L

    private fun row(id: Long, at: Long, type: Int = CallLog.Calls.OUTGOING_TYPE, seconds: Int = 60, component: String? = phone, account: String = "1", number: String? = "+4740000001") =
        CallLogRows.Row(id, at, seconds, type, number, CallLog.Calls.PRESENTATION_ALLOWED, component, account)

    private fun take(
        rows: List<CallLogRows.Row>,
        simCount: Int = 2,
        out: List<LongRange> = emptyList(),
        tests: List<Long> = emptyList(),
        recordings: List<CallIntake.Saved> = emptyList(),
    ) = CallIntake.take(rows, line, simCount, setup, out, tests, 7, "no", recordings) { "id" }

    @Test
    fun `the work line's calls since setup, and nothing else`() {
        val taken = take(
            listOf(
                row(1, setup - 1),                                // before setup
                row(2, setup + 10),                               // taken
                row(3, setup + 20, account = "2"),                // the other SIM
                row(4, setup + 30, component = "org.telegram.messenger/x"), // a messenger
                row(5, setup + 40, type = CallLog.Calls.MISSED_TYPE, seconds = 0), // missed: taken
                row(6, setup + 50, type = CallLog.Calls.BLOCKED_TYPE),           // blocked: not
            ),
        )
        assertEquals(listOf(2L, 5L), taken.map { it.callLogId })
        assertTrue(taken.all { it.userId == 7 && it.countryIso == "no" })
    }

    @Test
    fun `nothing from a stretch signed out on purpose`() {
        val taken = take(listOf(row(1, setup + 100), row(2, setup + 200), row(3, setup + 300)), out = listOf((setup + 150)..(setup + 250)))
        assertEquals(listOf(1L, 3L), taken.map { it.callLogId })
    }

    @Test
    fun `the answering machine's test calls are not the seller's`() {
        assertEquals(0, take(listOf(row(1, setup + 100_000)), tests = listOf(setup + 100_500)).size)
    }

    @Test
    fun `a looked-up number goes first, a hidden one waits out the window`() {
        val (shown, hidden) = take(listOf(row(1, setup + 10), row(2, setup + 20, number = null)))
        assertEquals(Local.NEW.name, shown.localState)
        assertEquals(0L, shown.dueAtMs)
        assertEquals(Local.WAITING.name, hidden.localState)
        assertEquals(hidden.endedAtMs + WorkCallMachine.HOLD_MS, hidden.dueAtMs)
    }

    @Test
    fun `a recording saved first is found, and a message is the machine's`() {
        val at = setup + 1_000_000
        val rec = CallIntake.Saved("20261103_101000_in.ogg", at + 4_000, incoming = true, message = true)
        val c = take(listOf(row(1, at, type = CallLog.Calls.INCOMING_TYPE)), recordings = listOf(rec)).single()
        assertEquals(rec.name, c.recording)
        assertTrue(c.byMachine)
        assertEquals("NO_ANSWER", c.outcome)
        assertEquals(Content.EXPECTED.name, c.contentState)
        val none = take(listOf(row(2, at, type = CallLog.Calls.OUTGOING_TYPE)), recordings = listOf(rec)).single()
        assertEquals("the other direction is another call", Content.NOT_EXPECTED.name, none.contentState)
    }
}
