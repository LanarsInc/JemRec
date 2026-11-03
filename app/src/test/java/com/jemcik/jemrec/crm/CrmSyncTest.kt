package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.db.WorkCallEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The queue against a local server answering with the API document's bodies. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
abstract class CrmSyncTestBase {

    protected val bed = CrmTestBed()
    protected val seen = mutableListOf<String>()
    protected val sync = CrmSync(bed.db.calls(), bed.store, bed.client, object : CrmSync.Events {
        override fun needsSeller(state: SessionStore.State, noticeOutdated: Boolean) {
            seen += if (noticeOutdated) "notice" else state.name
        }
    }, now = { bed.nowMs }, configured = { true })

    @Before
    fun signedIn() {
        bed.store.saveTestToken("0123456789abcdef0123456789abcdef01234567", 1000001, "Test Seller")
        bed.store.noticeAcknowledged("2026-11-02", bed.nowMs)
    }

    @After
    fun close() = bed.close()

    /** A call that ended a moment ago, as CallIntake takes it. */
    protected fun call(
        phone: String? = "+47 400 00 001",
        outgoing: Boolean = true,
        answered: Boolean = true,
        recording: String? = "rec.ogg",
        logId: Long = 1,
    ): Long = runBlocking {
        val start = bed.nowMs - 200_000
        bed.db.calls().insert(
            WorkCallEntry(
                clientCallId = "3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6$logId", callLogId = logId, userId = 1000001,
                outgoing = outgoing, answered = answered, phone = phone,
                presentation = if (phone == null) "RESTRICTED" else "ALLOWED", countryIso = "no",
                startedAtMs = start, durationSeconds = if (answered) 184 else 0, endedAtMs = start + 184_000,
                recording = recording, outcome = if (answered) "REACHED" else "NO_ANSWER",
                localState = (if (phone == null) Local.WAITING else Local.NEW).name,
                dueAtMs = if (phone == null) start + 184_000 + WorkCallMachine.PRIVATE_WINDOW_MS else 0,
                contentState = (if (recording != null) Content.EXPECTED else Content.NOT_EXPECTED).name,
            ),
        )
    }

    protected fun get(id: Long) = runBlocking { bed.db.calls().byId(id)!! }

    protected fun run(): Long? = runBlocking { sync.runDue() }

    protected fun minutes(m: Int) {
        bed.nowMs += m * 60_000L
    }
}

class CrmSyncTest : CrmSyncTestBase() {

    @org.junit.Test
    fun `a one-deal call is looked up, waits out the window, then is logged there by auto`() {
        val id = call()
        bed.answer(200, "lookup-deal")
        val next = run()
        assertEquals(listOf("POST /calls/lookup"), bed.requests().map { it.first })
        val waiting = get(id)
        assertEquals(Local.WAITING.name, waiting.localState)
        assertEquals(waiting.endedAtMs + WorkCallMachine.PRIVATE_WINDOW_MS, next)

        minutes(2)
        bed.answer(201, "call-logged")
        run()
        val (path, body) = bed.requests().single()
        assertEquals("POST /calls", path)
        val sent = JSONObject(body)
        assertEquals("deal", sent.getString("deal_choice"))
        assertEquals(1000456, sent.getInt("deal_id"))
        assertEquals("auto", sent.getString("chosen_by"))
        assertTrue(sent.getBoolean("transcript_expected"))
        val logged = get(id)
        assertEquals(Local.SENT.name, logged.localState)
        assertEquals(5012, logged.serverId)
        assertEquals(body, logged.stepOneBody)
    }

    @org.junit.Test
    fun `a colleague's number - nothing after the lookup`() {
        val id = call()
        bed.answer(200, "lookup-internal")
        run()
        minutes(5)
        run()
        assertEquals(listOf("POST /calls/lookup"), bed.requests().map { it.first })
        assertEquals(Local.INTERNAL.name, get(id).localState)
    }

    @org.junit.Test
    fun `a hidden number - no lookup, phone null`() {
        call(phone = null)
        minutes(2)
        bed.answer(201, "call-hidden")
        run()
        val (path, body) = bed.requests().single()
        assertEquals("POST /calls", path)
        assertTrue(JSONObject(body).isNull("phone"))
        assertEquals("auto", JSONObject(body).getString("deal_choice"))
    }

    @org.junit.Test
    fun `a missed call - no transcript expected, and no next call`() {
        val id = call(outgoing = false, answered = false, recording = null)
        bed.answer(200, "lookup-deal")
        run()
        val withNext = get(id).copy(nextJson = """{"due_date":"2026-11-04"}""")
        runBlocking { bed.db.calls().update(withNext) }
        minutes(2)
        bed.answer(201, "call-missed")
        run()
        val body = JSONObject(bed.requests().last().second)
        assertFalse(body.getBoolean("transcript_expected"))
        assertFalse(body.has("next"))
        assertEquals(0, body.getInt("duration_seconds"))
    }

    @org.junit.Test
    fun `Private before the window ends - nothing more is sent`() {
        val id = call()
        bed.answer(200, "lookup-deal")
        run()
        runBlocking { bed.db.calls().update(CrmActions.privateOf(get(id))!!) }
        minutes(5)
        run()
        assertEquals(listOf("POST /calls/lookup"), bed.requests().map { it.first })
        assertEquals(Local.PRIVATE.name, get(id).localState)
    }

    @org.junit.Test
    fun `a failed lookup is not retried - the server decides`() {
        val id = call()
        bed.answer(429, "error-rate-limited", "retry-after" to "60")
        run()
        assertNull(get(id).lookupJson)
        minutes(2)
        bed.answer(201, "call-not-in-crm")
        run()
        assertEquals(listOf("POST /calls/lookup", "POST /calls"), bed.requests().map { it.first })
        assertEquals("auto", JSONObject(get(id).stepOneBody!!).getString("deal_choice"))
    }

    @org.junit.Test
    fun `the seller's confirm sends at once`() {
        val id = call()
        bed.answer(200, "lookup-deal")
        run()
        val chosen = CrmActions.chosen(
            get(id),
            CrmActions.Choice(com.jemcik.jemrec.crm.api.DealChoice.DEAL, 1000456, com.jemcik.jemrec.crm.api.Outcome.CALLBACK, null),
            bed.nowMs,
        )!!
        runBlocking { bed.db.calls().update(chosen) }
        bed.answer(201, "call-logged")
        run()
        val body = JSONObject(bed.requests().last().second)
        assertEquals("user", body.getString("chosen_by"))
        assertEquals("callback", body.getString("outcome"))
    }

    @org.junit.Test
    fun `nothing goes before the notice is agreed`() {
        bed.store.noticeOutdated()
        call()
        assertNull(run())
        assertEquals(0, bed.server.requestCount)
    }
}
