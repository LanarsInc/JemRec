package com.jemcik.jemrec.crm.api

import com.jemcik.jemrec.crm.CrmFixtures
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

/** Every answer of the API document parses into what it says (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class ApiModelsTest {

    @Test
    fun `the exchange's answer`() {
        val s = ApiModels.session(CrmFixtures.data("session-201"))
        assertEquals("0123456789abcdef0123456789abcdef01234567", s.token)
        assertEquals("01234567", s.tokenPrefix)
        assertEquals(Instants.parse("2027-01-31T09:00:00.000Z"), s.expiresAtMs)
        assertEquals(30, s.idleTimeoutDays)
        assertEquals(UserInfo(1000001, "Test Seller", "android-test@example.no"), s.user)
        assertEquals("2026-11-02", s.noticeVersion)
        assertFalse(s.noticeAcknowledged)
        assertEquals("1.4.0", s.minAppVersion)
    }

    @Test
    fun `no minimum version is null, not the text null`() {
        val data = CrmFixtures.data("session-201").put("min_app_version", JSONObject.NULL)
        assertNull(ApiModels.session(data).minAppVersion)
    }

    @Test
    fun `the notice keeps every kind of text`() {
        val n = ApiModels.notice(CrmFixtures.data("notice"))
        assertEquals("2026-11-02", n.version)
        assertEquals(2, n.sections.size)
        assertEquals(2, n.sections[0].items.size)
        assertEquals(listOf("An invented paragraph."), n.sections[1].paragraphs)
        assertEquals(2, n.sections[1].ordered.size)
    }

    @Test
    fun `a lookup with deals`() {
        val l = ApiModels.lookup(CrmFixtures.data("lookup-deal"))
        assertEquals(Match.DEAL, l.match)
        assertEquals(2, l.candidates.size)
        assertEquals(1000456, l.autoDealId)
        assertEquals(1000456, l.suggestedDealId)
        assertEquals(DealStatus.LOST, l.candidates[1].status)
        assertTrue(l.candidates[0].hasDueCall)
        assertEquals("Test Seller", l.candidates[0].ownerName)
    }

    @Test
    fun `a deal with no organisation`() {
        assertNull(ApiModels.lookup(CrmFixtures.data("lookup-no-org")).candidates.single().orgName)
    }

    @Test
    fun `internal and none, and an unknown match counts as none`() {
        assertEquals(Match.INTERNAL, ApiModels.lookup(CrmFixtures.data("lookup-internal")).match)
        assertEquals(Match.NONE, ApiModels.lookup(CrmFixtures.data("lookup-none")).match)
        val unknown = CrmFixtures.data("lookup-none").put("match", "maybe")
        assertEquals(Match.NONE, ApiModels.lookup(unknown).match)
    }

    @Test
    fun `every call object of the document`() {
        val logged = ApiModels.call(CrmFixtures.data("call-logged"))
        assertEquals(5012, logged.id)
        assertEquals(CallStatus.LOGGED, logged.status)
        assertEquals(1000456, logged.dealId)
        assertTrue(logged.completedPlannedCall)
        assertEquals(ContentStatus.EXPECTED, logged.contentStatus)

        val pending = ApiModels.call(CrmFixtures.data("call-pending"))
        assertEquals(CallStatus.PENDING, pending.status)
        assertFalse(pending.status.isFinal)
        assertEquals(PendingReason.SEVERAL_OPEN_DEALS, pending.pendingReason)
        assertEquals(2, pending.candidates.size)

        assertEquals(1000792, ApiModels.call(CrmFixtures.data("call-missed")).callbackActivityId)
        assertEquals(CallStatus.NOT_IN_CRM, ApiModels.call(CrmFixtures.data("call-not-in-crm")).status)
        assertEquals(CallStatus.HIDDEN, ApiModels.call(CrmFixtures.data("call-hidden")).status)
        assertEquals(CallStatus.DECLINED, ApiModels.call(CrmFixtures.data("call-declined")).status)
        val internal = ApiModels.call(CrmFixtures.data("call-internal"))
        assertNull(internal.id)
        assertEquals(CallStatus.INTERNAL, internal.status)
        assertEquals(CallStatus.PRIVATE, ApiModels.call(CrmFixtures.data("call-private-repeat")).status)
        assertEquals(PendingReason.DEAL_NOT_CANDIDATE, ApiModels.call(CrmFixtures.data("deal-not-candidate")).pendingReason)
        assertEquals(ContentStatus.READY, ApiModels.call(CrmFixtures.data("content-ready")).contentStatus)
        assertEquals(ContentStatus.FAILED, ApiModels.call(CrmFixtures.data("content-failed")).contentStatus)
        assertEquals(CallStatus.PRIVATE, ApiModels.call(CrmFixtures.data("private")).status)
    }

    @Test
    fun `unknown status is final and unknown reason is several open deals`() {
        val data = CrmFixtures.data("call-pending")
        data.getJSONObject("call").put("status", "archived").put("pending_reason", "something_new").put("colour", "blue")
        val c = ApiModels.call(data)
        assertEquals(CallStatus.OTHER, c.status)
        assertTrue(c.status.isFinal)
        assertEquals(PendingReason.SEVERAL_OPEN_DEALS, c.pendingReason)
    }

    @Test
    fun `a call object survives being kept and read back`() {
        for (name in listOf("call-logged", "call-pending", "call-internal", "deal-not-candidate")) {
            val c = ApiModels.call(CrmFixtures.data(name))
            assertEquals(name, c, ApiModels.call(ApiModels.callJson(c)))
        }
        val l = ApiModels.lookup(CrmFixtures.data("lookup-deal"))
        assertEquals(l, ApiModels.lookup(ApiModels.lookupJson(l)))
    }
}
