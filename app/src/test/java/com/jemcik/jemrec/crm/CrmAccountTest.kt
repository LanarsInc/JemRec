package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.api.ApiFailure
import com.jemcik.jemrec.crm.db.WorkCallEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CrmAccountTest {

    private val bed = CrmTestBed()
    private val account = CrmAccount(bed.client, bed.store, bed.db.calls(), CrmAccount.Device("Example Phone 12", "1.4.0", 35)) { bed.nowMs }

    @After
    fun close() = bed.close()

    private fun call(userId: Int, logId: Long) = WorkCallEntry(
        clientCallId = "c$logId", callLogId = logId, userId = userId, outgoing = true, answered = true, phone = "1",
        presentation = "ALLOWED", countryIso = null, startedAtMs = 0, durationSeconds = 1, endedAtMs = 1,
        outcome = "REACHED", localState = "WAITING", dueAtMs = 0, contentState = "NOT_EXPECTED",
    )

    @Test
    fun `a sign-in keeps the session, never the Google token`() = runBlocking {
        bed.answer(201, "session-201")
        val r = account.signIn("<Google ID token>")
        assertEquals(CrmAccount.SignIn.Done("Test Seller"), r)
        assertEquals("0123456789abcdef0123456789abcdef01234567", bed.store.token())
        assertEquals(SessionStore.State.ACTIVE, bed.store.state)
        assertFalse(bed.store.noticeOk) // the answer said acknowledged: false
        val (path, body) = bed.requests().single()
        assertEquals("POST /mobile/session", path)
        assertTrue(body.contains("\"label\":\"Example Phone 12\""))
        assertTrue(bed.store.deviceId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }

    @Test
    fun `the device id is made once and kept`() {
        assertEquals(bed.store.deviceId, bed.store.deviceId)
    }

    @Test
    fun `another person signing in drops what was queued for the one before`() = runBlocking {
        bed.answer(201, "session-201")
        account.signIn("t")
        bed.db.calls().insert(call(1000001, 1))
        bed.db.calls().insert(call(1000001, 2))
        val other = CrmFixtures.json("session-201").apply { getJSONObject("data").getJSONObject("user").put("id", 1000002) }
        bed.answerJson(201, other.toString())
        account.signIn("t2")
        assertEquals(0, bed.db.calls().all().size)
    }

    @Test
    fun `the same person again keeps the queue`() = runBlocking {
        bed.answer(201, "session-201")
        account.signIn("t")
        bed.db.calls().insert(call(1000001, 1))
        bed.answer(201, "session-201")
        account.signIn("t2")
        assertEquals(1, bed.db.calls().all().size)
    }

    @Test
    fun `refusals say what to do`() = runBlocking {
        bed.answer(403, "error-session-no_user")
        assertEquals(CrmAccount.SignIn.Refused("This account is not a Sales Engine user. Ask LANARS to add you."), account.signIn("t"))
        bed.answer(403, "error-session-domain_not_allowed")
        assertEquals(CrmAccount.SignIn.Refused("Use your lanars.com or lanars.no account."), account.signIn("t"))
        bed.answer(401, "error-unauthorized")
        assertEquals(CrmAccount.SignIn.Refused(CrmAccount.message(ApiFailure.SignInFailed)), account.signIn("t"))
    }

    @Test
    fun `a blocked phone forgets the session and every queued call`() = runBlocking {
        bed.answer(201, "session-201")
        account.signIn("t")
        bed.db.calls().insert(call(1000001, 1))
        bed.answer(403, "error-session-device_blocked")
        assertEquals(CrmAccount.SignIn.Blocked, account.signIn("t2"))
        assertEquals(0, bed.db.calls().all().size)
        assertNull(bed.store.token())
        assertEquals(SessionStore.State.BLOCKED, bed.store.state)
    }

    @Test
    fun `a minimum above this version holds everything`() = runBlocking {
        val newer = CrmFixtures.json("session-201").apply { getJSONObject("data").put("min_app_version", "2.0.0") }
        bed.answerJson(201, newer.toString())
        assertEquals(CrmAccount.SignIn.UpgradeRequired("2.0.0"), account.signIn("t"))
        assertEquals(SessionStore.State.UPGRADE_REQUIRED, bed.store.state)
    }

    @Test
    fun `renewal is due in the last seven days`() = runBlocking {
        bed.answer(201, "session-201") // expires 2027-01-31T09:00Z
        account.signIn("t")
        bed.nowMs = com.jemcik.jemrec.crm.api.Instants.parse("2027-01-20T09:00:00.000Z")
        assertFalse(account.needsRenewal())
        bed.nowMs = com.jemcik.jemrec.crm.api.Instants.parse("2027-01-25T09:00:00.000Z")
        assertTrue(account.needsRenewal())
    }

    @Test
    fun `signing out is done even offline, and marks the moment`() = runBlocking {
        bed.answer(201, "session-201")
        account.signIn("t")
        bed.server.shutdown()
        account.signOut()
        assertNull(bed.store.token())
        assertEquals(SessionStore.State.SIGNED_OUT, bed.store.state)
        assertEquals(bed.nowMs, bed.store.signedOutAtMs)
    }

    /** The stand, 2026-11-05: an agreement kept from before a sign-in the server says has none. */
    @Test
    fun `a sign-in the server says has not agreed asks for the notice again`() = runBlocking {
        bed.store.noticeAcknowledged("2026-11-03", 1)
        bed.answer(201, "session-201")
        account.signIn("t")
        assertFalse(bed.store.noticeOk)
    }

    @Test
    fun `the notice - agreed, and a changed version is told apart`() = runBlocking {
        bed.answer(201, "session-201")
        account.signIn("t")
        bed.answerJson(200, """{"data":{"version":"2026-11-03","acknowledged_at":"2026-11-03T08:01:12.000Z"}}""")
        assertNull(account.acknowledge("2026-11-03"))
        assertTrue(bed.store.noticeOk)
        bed.answer(409, "error-notice-conflict")
        assertEquals(ApiFailure.NoticeChanged, account.acknowledge("2026-10-01"))
    }
}
