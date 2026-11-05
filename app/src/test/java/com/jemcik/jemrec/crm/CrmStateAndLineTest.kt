package com.jemcik.jemrec.crm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmStateAndLineTest {

    private fun state(
        configured: Boolean = true,
        session: SessionStore.State = SessionStore.State.ACTIVE,
        notice: Boolean = true,
        callLog: Boolean = true,
        line: Boolean = true,
    ) = CrmState.of(configured, session, notice, callLog, line)

    @Test
    fun `the seller is asked one thing at a time, in order`() {
        assertEquals(CrmState.NOT_CONFIGURED, state(configured = false, session = SessionStore.State.SIGNED_OUT))
        assertEquals(CrmState.NOT_CONNECTED, state(session = SessionStore.State.SIGNED_OUT, notice = false))
        assertEquals(CrmState.BLOCKED, state(session = SessionStore.State.BLOCKED))
        assertEquals(CrmState.UPGRADE_REQUIRED, state(session = SessionStore.State.UPGRADE_REQUIRED))
        assertEquals(CrmState.NEEDS_SIGN_IN, state(session = SessionStore.State.NEEDS_SIGN_IN, notice = false))
        assertEquals(CrmState.NEEDS_NOTICE, state(notice = false, callLog = false))
        assertEquals(CrmState.NEEDS_CALL_LOG, state(callLog = false, line = false))
        assertEquals(CrmState.NEEDS_LINE, state(line = false))
        assertEquals(CrmState.CONNECTED, state())
    }

    @Test
    fun `calls queue while the seller fixes something, not after a sign-out or a block`() {
        assertTrue(CrmState.CONNECTED.sends)
        assertFalse(CrmState.NEEDS_SIGN_IN.sends)
        assertTrue(CrmState.NEEDS_SIGN_IN.queues)
        assertTrue(CrmState.NEEDS_NOTICE.queues)
        assertFalse(CrmState.NOT_CONNECTED.queues)
        assertFalse(CrmState.BLOCKED.queues)
    }

    private val sim1 = WorkLine.Sim(1, 0, "Work", "Telia", "8947080012345678901", "no")
    private val phone = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"

    @Test
    fun `a call is on the line by subscription id or ICCID`() {
        assertTrue(WorkLine.matches(phone, "1", sim1, simCount = 2))
        assertFalse(WorkLine.matches(phone, "2", sim1, simCount = 2))
        assertTrue(WorkLine.matches(phone, "8947080012345678901", sim1, simCount = 2))
        assertTrue(WorkLine.matches(phone, "894708001234567890", sim1, simCount = 2)) // check digit cut
        assertFalse(WorkLine.matches(phone, "", sim1, simCount = 2))
    }

    @Test
    fun `with one SIM every phone call is the line's, and no app's call ever is`() {
        assertTrue(WorkLine.matches(phone, "whatever", sim1, simCount = 1))
        assertTrue(WorkLine.matches(null, null, sim1, simCount = 1)) // rows from before Android kept an account
        assertFalse(WorkLine.matches("com.google.android.apps.tachyon/x", "1", sim1, simCount = 1))
        assertFalse(WorkLine.matches("org.telegram.messenger/x", "1", sim1, simCount = 2))
    }

    @Test
    fun `the label names the slot, never the number`() {
        assertEquals("SIM 1 · Work · Telia", sim1.label)
        assertEquals("SIM 2 · Telenor", WorkLine.Sim(2, 1, "Telenor", "Telenor", null, null).label)
    }
}
