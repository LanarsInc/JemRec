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

/** A call waiting for a deal or a lead, and the seller's pick (§7). */
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
    fun `Other`() {
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
    fun `a logged call cannot be chosen again here`() {
        val id = call()
        bed.answer(200, "lookup-deal")
        run()
        minutes(2)
        bed.answer(201, "call-logged")
        run()
        val sheet = CallSheetUi.of(get(id), false)
        assertFalse(sheet.canChoose)
        assertTrue(sheet.note!!.contains("web app"))
        assertEquals(Local.SENT.name, get(id).localState)
    }

    /** A weekly-goal seller's call to a number with no deal: pending as no_match (§6.1). */
    private fun noMatch(): Long {
        val id = call(phone = "+47 400 00 009")
        bed.answer(200, "lookup-none")
        run()
        minutes(2)
        bed.answer(201, "call-no-match")
        run()
        bed.requests()
        return id
    }

    @Test
    fun `no_match - Which lead, with suggestions and none ticked`() {
        val id = noMatch()
        val sheet = CallSheetUi.of(get(id), false)
        assertTrue(sheet.whichLead)
        assertTrue(sheet.canChoose)
        assertNull(sheet.preselected)
        assertEquals(listOf(1000459, 1000456), sheet.candidates.map { it.dealId })
        assertEquals("Which lead?", CallSheetUi.markText(get(id)))
        assertTrue(CallSheetUi.needsSeller(get(id)))
    }

    @Test
    fun `no_match - a deal found by search is picked and logged`() {
        val id = noMatch()
        // 1000458 is not among the suggestions: any open deal may be picked (§7).
        pick(id, DealChoice.DEAL, 1000458)
        bed.answer(200, "deal-found-logged")
        run()
        val (path, body) = bed.requests().single()
        assertEquals("POST /calls/5015/deal", path)
        assertTrue(CrmFixtures.same(CrmFixtures.json("deal-found-request"), JSONObject(body)))
        assertEquals(CallStatus.LOGGED, status(id))
    }

    @Test
    fun `no_match - a lead the server cannot take leaves it waiting with fresh suggestions`() {
        val id = noMatch()
        pick(id, DealChoice.DEAL, 1000458)
        bed.answer(200, "call-no-match")
        run()
        assertEquals(CallStatus.PENDING, status(id))
        assertTrue(CallSheetUi.of(get(id), false).whichLead)
        assertNull(get(id).pickChoice)
    }

    @Test
    fun `no_match - Other`() {
        val id = noMatch()
        pick(id, DealChoice.NONE, null)
        bed.answer(200, "deal-declined")
        run()
        assertEquals("""{"deal_choice":"none"}""", bed.requests().single().second)
        assertEquals("Other: not on a deal", CallSheetUi.markText(get(id)))
    }

    @Test
    fun `before sending, the sheet shows the lookup's deals with the single open one ticked`() {
        val id = call()
        bed.answer(200, "lookup-deal")
        run()
        val sheet = CallSheetUi.of(get(id), false)
        assertTrue(sheet.beforeSending)
        assertEquals(1000456, sheet.preselected)
    }
}
