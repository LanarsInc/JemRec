package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.api.Bodies
import com.jemcik.jemrec.crm.api.Content as Texts
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 through the queue (§8). */
class CrmSyncContentTest : CrmSyncTestBase() {

    private fun logged(): Long {
        val id = call()
        bed.answer(200, "lookup-deal")
        run()
        minutes(2)
        bed.answer(201, "call-logged")
        run()
        bed.requests()
        return id
    }

    private val texts = Texts("Customer wants a quote.\n• Decision maker: the CEO.", "Seller: Hei.\nCustomer: Hei.", "nb", false)

    @Test
    fun `texts made after the call was logged go at once`() {
        val id = logged()
        runBlocking { bed.db.calls().update(ContentHook.ready(get(id), Bodies.ready(texts))!!) }
        bed.answer(200, "content-ready")
        run()
        val (path, body) = bed.requests().single()
        assertEquals("POST /calls/5012/content", path)
        assertEquals("en", JSONObject(body).getString("summary_language"))
        assertEquals(Content.SENT_READY.name, get(id).contentState)
    }

    @Test
    fun `texts made before step 1 wait for the call's id`() {
        val id = call()
        runBlocking { bed.db.calls().update(ContentHook.ready(get(id), Bodies.ready(texts))!!) }
        bed.answer(200, "lookup-deal")
        run()
        assertEquals(listOf("POST /calls/lookup"), bed.requests().map { it.first })
        minutes(2)
        bed.answer(201, "call-logged")
        bed.answer(200, "content-ready")
        run()
        assertEquals(listOf("POST /calls", "POST /calls/5012/content"), bed.requests().map { it.first })
    }

    @Test
    fun `nothing in 72 hours - failed, and a later success once`() {
        val id = logged()
        minutes(72 * 60)
        bed.answer(200, "content-failed")
        run()
        assertTrue(CrmFixtures.same(CrmFixtures.json("content-failed-request"), JSONObject(bed.requests().single().second)))
        assertEquals(Content.SENT_FAILED.name, get(id).contentState)
        runBlocking { bed.db.calls().update(ContentHook.ready(get(id), Bodies.ready(texts))!!) }
        bed.answer(200, "content-ready")
        run()
        assertEquals(Content.SENT_READY.name, get(id).contentState)
    }

    @Test
    fun `other texts after ready - the server keeps what it has`() {
        val id = logged()
        runBlocking { bed.db.calls().update(get(id).copy(contentState = Content.READY_TO_SEND.name, contentBody = Bodies.ready(texts))) }
        bed.answerJson(409, """{"error":"duplicate","detail":"other content"}""")
        bed.answer(200, "content-ready")
        run()
        assertEquals(listOf("POST /calls/5012/content", "GET /calls/5012"), bed.requests().map { it.first })
        assertEquals(Content.DONE.name, get(id).contentState)
    }

    /** Send the transcript again (LANARS, 2026-11-05): the next revision replaces the server's texts. */
    @Test
    fun `texts made again go with the next revision`() {
        val id = logged()
        runBlocking { bed.db.calls().update(ContentHook.ready(get(id), Bodies.ready(texts))!!) }
        bed.answer(200, "content-ready")
        run()
        bed.requests()
        val asked = WorkCallMachine.resend(get(id).copy(recording = "a.ogg"))
        assertEquals(Content.RESENDING.name, asked.contentState)
        runBlocking { bed.db.calls().update(ContentHook.ready(asked, Bodies.ready(texts, asked.contentRevision))!!) }
        bed.answer(200, "content-ready")
        run()
        val (path, body) = bed.requests().single()
        assertEquals("POST /calls/5012/content", path)
        assertEquals(1, JSONObject(body).getInt("revision"))
        assertEquals(Content.SENT_READY.name, get(id).contentState)
        assertEquals(1, get(id).contentRevision)
    }

    /** Refused (the same revision with other texts is 409): the server keeps the first, and the line says so. */
    @Test
    fun `texts made again and refused - the first stay, and it is said`() {
        val id = logged()
        val asked = WorkCallMachine.resend(get(id).copy(contentState = Content.SENT_READY.name, recording = "a.ogg"))
        runBlocking { bed.db.calls().update(ContentHook.ready(asked, Bodies.ready(texts, asked.contentRevision))!!) }
        bed.answerJson(409, """{"error":"duplicate","detail":"other content already stored for this call"}""")
        bed.answer(200, "content-ready")
        run()
        assertEquals(listOf("POST /calls/5012/content", "GET /calls/5012"), bed.requests().map { it.first })
        assertEquals(Content.SENT_READY.name, get(id).contentState)
        assertEquals("not replaced", get(id).lastError)
    }

    @Test
    fun `no recording - no step 2`() {
        val id = call(recording = null)
        bed.answer(200, "lookup-deal")
        run()
        minutes(2)
        bed.answer(201, "call-logged")
        run()
        minutes(73 * 60)
        run()
        assertEquals(listOf("POST /calls/lookup", "POST /calls"), bed.requests().map { it.first })
        assertEquals(Content.NOT_EXPECTED.name, get(id).contentState)
    }
}
