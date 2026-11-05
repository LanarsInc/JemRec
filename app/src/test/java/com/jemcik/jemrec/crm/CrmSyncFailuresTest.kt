package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.WorkCallMachine.Local
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls wait and go once: §11's failures acted on (FR-040 .. FR-046). */
class CrmSyncFailuresTest : CrmSyncTestBase() {

    /** A call past its lookup and its window: step 1 is due now. */
    private fun due(): Long {
        val id = call(phone = null)
        minutes(2)
        return id
    }

    @Test
    fun `no network - backoff, then sent once`() {
        val id = due()
        bed.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val next = run()!!
        assertEquals(Local.WAITING.name, get(id).localState)
        assertEquals(bed.nowMs + 60_000, next)
        minutes(1)
        bed.answer(201, "call-hidden")
        run()
        assertEquals(Local.SENT.name, get(id).localState)
        // The retry carried the body built the first time (§6.3); the dropped
        // connection never let the server read the first one.
        assertEquals(get(id).stepOneBody, bed.requests().last().second)
    }

    @Test
    fun `cut after the server stored it - the resend answers 200 and it is one call`() {
        val id = due()
        bed.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        run()
        minutes(1)
        bed.answer(200, "call-hidden")
        run()
        assertEquals(5016, get(id).serverId)
        assertEquals(1, bed.db.calls().let { kotlinx.coroutines.runBlocking { it.all() } }.size)
    }

    @Test
    fun `an HTML gateway page is no reason to sign in again`() {
        due()
        bed.answerJson(502, "<html>Bad gateway</html>")
        run()
        assertEquals(SessionStore.State.ACTIVE, bed.store.state)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `429 waits as long as it is told`() {
        val id = due()
        bed.answer(429, "error-rate-limited", "retry-after" to "300")
        assertEquals(bed.nowMs + 300_000, run())
        assertEquals("rate limited", get(id).lastError)
    }

    @Test
    fun `401 holds everything until a sign-in, and asks for one`() {
        val id = call(phone = null)
        call(phone = null, logId = 2)
        minutes(2)
        bed.answer(401, "error-unauthorized")
        assertNull(run())
        assertEquals(1, bed.server.requestCount) // the second call was not tried
        assertEquals(SessionStore.State.NEEDS_SIGN_IN, bed.store.state)
        assertEquals(listOf("NEEDS_SIGN_IN"), seen)
        assertEquals(Local.WAITING.name, get(id).localState)
        // Signed in again: both go.
        bed.store.saveTestToken("0123456789abcdef0123456789abcdef01234567", 1000001, "Test Seller")
        bed.answer(201, "call-hidden")
        bed.answer(201, "call-hidden")
        run()
        assertEquals(3, bed.server.requestCount)
    }

    @Test
    fun `a changed notice holds the queue and asks to read it`() {
        due()
        bed.answer(403, "error-notice-required")
        assertNull(run())
        assertFalse(bed.store.noticeOk)
        assertEquals(listOf("notice"), seen)
    }

    @Test
    fun `426 holds everything and asks for an update`() {
        due()
        bed.answer(426, "error-upgrade")
        run()
        assertEquals(SessionStore.State.UPGRADE_REQUIRED, bed.store.state)
        assertEquals(listOf("UPGRADE_REQUIRED"), seen)
    }

    @Test
    fun `a next call the server refuses is dropped once, the call still goes`() {
        val id = due()
        val withNext = get(id).copy(nextJson = """{"due_date":"2026-11-01"}""")
        kotlinx.coroutines.runBlocking { bed.db.calls().update(withNext) }
        bed.answer(400, "error-next")
        bed.answer(201, "call-hidden")
        run()
        val bodies = bed.requests().map { JSONObject(it.second) }
        assertTrue(bodies[0].has("next"))
        assertFalse(bodies[1].has("next"))
        assertEquals(Local.SENT.name, get(id).localState)
    }

    @Test
    fun `a phone clock ahead of the server waits an hour`() {
        due()
        bed.answerJson(400, """{"error":"invalid_input","detail":"started_at: must not be in the future"}""")
        assertEquals(bed.nowMs + 60 * 60_000, run())
    }

    @Test
    fun `a call older than 30 days is marked, not sent`() {
        val id = due()
        minutes(30 * 24 * 60)
        run()
        assertEquals(0, bed.server.requestCount)
        assertEquals(Local.NOT_SENT.name, get(id).localState)
        assertEquals("too old", get(id).lastError)
    }

    @Test
    fun `a call the server no longer has is marked`() {
        val id = call()
        bed.answer(200, "lookup-none")
        run()
        minutes(2)
        bed.answer(201, "call-pending")
        run()
        // A pick for it, and the server has lost it meanwhile.
        kotlinx.coroutines.runBlocking { bed.db.calls().update(CrmActions.chosen(get(id), CrmActions.Choice(com.jemcik.jemrec.crm.api.DealChoice.NONE, null, com.jemcik.jemrec.crm.api.Outcome.REACHED, null), bed.nowMs)!!) }
        bed.answer(404, "error-call-not-found")
        run()
        assertEquals(Local.NOT_SENT.name, get(id).localState)
    }

    @Test
    fun `the server older than this app - hold for hours`() {
        due()
        bed.answer(404, "error-unknown-endpoint")
        assertEquals(bed.nowMs + 6 * 60 * 60_000, run())
    }
}
