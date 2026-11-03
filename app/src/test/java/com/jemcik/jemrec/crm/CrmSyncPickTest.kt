package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.CallStatus
import com.jemcik.jemrec.crm.api.DealChoice
import com.jemcik.jemrec.crm.api.NextCall
import com.jemcik.jemrec.crm.api.Outcome
import com.jemcik.jemrec.crm.api.PendingReason
import com.jemcik.jemrec.ui.CallSheetUi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call waiting for a deal, the seller's pick, and Make private (§7, §10). */
class CrmSyncPickTest : CrmSyncTestBase() {

    private fun status(id: Long) = ApiModels.call(JSONObject(get(id).serverJson!!)).status

    /** A two-deal call, sent as auto, now pending. */
    private fun pending(): Long {
        val id = call()
        val lookup = CrmFixtures.data("lookup-deal").put("auto_deal_id", JSONObject.NULL)
        bed.answerJson(200, JSONObject().put("data", lookup).toString())
        run()
        minutes(2)
        bed.answer(201, "call-pending")
        run()
        bed.requests()
        assertEquals(CallStatus.PENDING, status(id))
        return id
    }

    private fun pick(id: Long, choice: DealChoice, deal: Int?, next: NextCall? = null) = runBlocking {
        bed.db.calls().update(CrmActions.chosen(get(id), CrmActions.Choice(choice, deal, Outcome.REACHED, next), bed.nowMs)!!)
    }

    @Test
    fun `several open deals - sent as auto, the seller picks later, logged there`() {
        val id = pending()
        val sheet = CallSheetUi.of(get(id), loading = false)
        assertTrue(sheet.canChoose)
        assertEquals(2, sheet.candidates.size)
        pick(id, DealChoice.DEAL, 1000458, NextCall("2026-11-06"))
        bed.answer(200, "deal-logged")
        run()
        val (path, body) = bed.requests().single()
        assertEquals("POST /calls/5013/deal", path)
        assertTrue(CrmFixtures.same(CrmFixtures.json("deal-request"), JSONObject(body)))
        assertEquals(CallStatus.LOGGED, status(id))
        assertNull(get(id).pickChoice)
    }

    @Test
    fun `a stale pick comes back pending with fresh deals`() {
        val id = pending()
        pick(id, DealChoice.DEAL, 1000458)
        bed.answer(200, "deal-not-candidate")
        run()
        val s = ApiModels.call(JSONObject(get(id).serverJson!!))
        assertEquals(CallStatus.PENDING, s.status)
        assertEquals(PendingReason.DEAL_NOT_CANDIDATE, s.pendingReason)
        assertEquals(listOf(1000456), CallSheetUi.of(get(id), false).candidates.map { it.dealId })
    }

    @Test
    fun `Don't log`() {
        val id = pending()
        pick(id, DealChoice.NONE, null, NextCall("2026-11-06"))
        bed.answer(200, "deal-declined")
        run()
        assertTrue(CrmFixtures.same(CrmFixtures.json("deal-none-request"), JSONObject(bed.requests().single().second)))
        assertEquals(CallStatus.DECLINED, status(id))
    }

    @Test
    fun `another pick after the call was logged - adopt the server's state`() {
        val id = pending()
        pick(id, DealChoice.DEAL, 1000458)
        bed.answer(409, "error-duplicate-deal")
        bed.answer(200, "call-logged")
        run()
        assertEquals(listOf("POST /calls/5013/deal", "GET /calls/5013"), bed.requests().map { it.first })
        assertEquals(CallStatus.LOGGED, status(id))
    }

    @Test
    fun `Make private on a call not in the CRM`() {
        val id = call()
        bed.answer(200, "lookup-none")
        run()
        minutes(2)
        bed.answer(201, "call-not-in-crm")
        run()
        bed.requests()
        assertEquals("Make private", CallSheetUi.of(get(id), false).privateLabel)
        runBlocking { bed.db.calls().update(CrmActions.privateOf(get(id))!!) }
        bed.answer(200, "private")
        run()
        assertEquals("POST /calls/5015/private", bed.requests().single().first)
        assertEquals(CallStatus.PRIVATE, status(id))
        assertFalse(get(id).privateAsked)
    }

    @Test
    fun `a logged call cannot be made private here`() {
        val id = call()
        bed.answer(200, "lookup-deal")
        run()
        minutes(2)
        bed.answer(201, "call-logged")
        run()
        assertNull(CrmActions.privateOf(get(id)))
        val sheet = CallSheetUi.of(get(id), false)
        assertNull(sheet.privateLabel)
        assertFalse(sheet.canChoose)
        assertTrue(sheet.note!!.contains("web app"))
        assertEquals(Local.SENT.name, get(id).localState)
    }

    @Test
    fun `before sending, the sheet shows the lookup's deals with the single open one ticked`() {
        val id = call()
        bed.answer(200, "lookup-deal")
        run()
        val sheet = CallSheetUi.of(get(id), false)
        assertTrue(sheet.beforeSending)
        assertEquals(1000456, sheet.preselected)
        assertEquals("Private", sheet.privateLabel)
    }
}
