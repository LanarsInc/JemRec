package com.jemcik.jemrec.crm.api

import com.jemcik.jemrec.crm.CrmFixtures
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The client against a real local server answering with the document's bodies. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class SalesEngineClientTest {

    private lateinit var server: MockWebServer
    private var token: String? = "0123456789abcdef0123456789abcdef01234567"
    private lateinit var client: SalesEngineClient

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        client = SalesEngineClient(server.url("/api/v1").toString().trimEnd('/'), "1.0.0-debug") { token }
    }

    @After
    fun stop() = server.shutdown()

    private fun answer(status: Int, fixture: String, vararg headers: Pair<String, String>) =
        server.enqueue(
            MockResponse().setResponseCode(status).setBody(CrmFixtures.text(fixture))
                .setHeader("content-type", "application/json").also { r -> headers.forEach { r.setHeader(it.first, it.second) } },
        )

    @Test
    fun `headers - token, version, never Authorization`() {
        answer(200, "lookup-deal")
        client.lookup("400 00 001", "no")
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/calls/lookup", r.path)
        assertEquals(token, r.getHeader("x-api-token"))
        assertEquals("1.0.0-debug", r.getHeader("x-app-version"))
        assertTrue(r.getHeader("content-type")!!.startsWith("application/json"))
        assertNull(r.getHeader("Authorization"))
        assertNull(r.getHeader("Idempotency-Key"))
        assertTrue(CrmFixtures.same(CrmFixtures.json("lookup-request"), org.json.JSONObject(r.body.readUtf8())))
    }

    @Test
    fun `the exchange carries no token`() {
        answer(201, "session-201")
        val r = client.exchange(CrmFixtures.text("session-request"))
        assertEquals("01234567", (r as ApiResult.Ok).value.tokenPrefix)
        assertTrue(r.created)
        assertNull(server.takeRequest().getHeader("x-api-token"))
    }

    @Test
    fun `the deal search - its request and the deals, best match first`() {
        answer(200, "search")
        val deals = (client.search("Eksempel") as ApiResult.Ok).value
        assertEquals(listOf(1000458, 1000456), deals.map { it.dealId })
        val r = server.takeRequest()
        assertTrue(CrmFixtures.same(CrmFixtures.json("search-request"), JSONObject(r.body.readUtf8())))
    }

    @Test
    fun `201 and 200 on step 1`() {
        answer(201, "call-logged")
        assertTrue((client.postCall(CrmFixtures.text("call-request")) as ApiResult.Ok).created)
        answer(200, "call-logged")
        val again = client.postCall(CrmFixtures.text("call-request")) as ApiResult.Ok
        assertEquals(false, again.created)
        assertEquals(CallStatus.LOGGED, again.value.status)
    }

    @Test
    fun `every call endpoint's path and method`() {
        answer(200, "deal-logged"); client.pickDeal(5013, Bodies.pick(DealChoice.NONE, null, null))
        answer(200, "content-ready"); client.postContent(5012, Bodies.failed())
        answer(200, "get-call"); client.getCall(5013)
        answer(200, "search"); client.search("Eksempel")
        answer(200, "notice"); client.notice()
        answer(200, "me"); client.me()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"signed_out":true}}"""))
        client.signOut()
        val seen = (1..7).map { server.takeRequest().let { r -> "${r.method} ${r.path}" } }
        assertEquals(
            listOf(
                "POST /api/v1/calls/5013/deal", "POST /api/v1/calls/5012/content", "GET /api/v1/calls/5013",
                "POST /api/v1/calls/search", "GET /api/v1/mobile/notice", "GET /api/v1/me", "DELETE /api/v1/mobile/session",
            ),
            seen,
        )
    }

    @Test
    fun `a redirect and an HTML 502 are network errors`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("location", "https://portal.example/"))
        assertEquals(ApiResult.Failed(ApiFailure.Retry), client.getCall(5013))
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        assertEquals(ApiResult.Failed(ApiFailure.Retry), client.getCall(5013))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Log in to the Wi-Fi</html>"))
        assertEquals(ApiResult.Failed(ApiFailure.Retry), client.getCall(5013))
    }

    @Test
    fun `errors reach the table, retry-after included`() {
        answer(429, "error-rate-limited", "retry-after" to "42")
        assertEquals(ApiResult.Failed(ApiFailure.RetryAfter(42)), client.postContent(5012, Bodies.failed()))
        answer(401, "error-unauthorized")
        assertEquals(ApiResult.Failed(ApiFailure.SessionDead), client.getCall(5013))
        answer(403, "error-session-device_blocked")
        assertEquals(ApiResult.Failed(ApiFailure.Blocked), client.exchange("{}"))
        answer(409, "error-duplicate-facts")
        assertEquals(ApiResult.Failed(ApiFailure.Adopt), client.postCall("{}"))
        answer(426, "error-upgrade")
        assertEquals(ApiResult.Failed(ApiFailure.UpgradeRequired("minimum app version 1.4.0")), client.me())
    }

    @Test
    fun `a sign-out on a dead token counts as signed out`() {
        answer(401, "error-unauthorized")
        assertEquals(ApiResult.Ok(Unit), client.signOut())
    }

    @Test
    fun `no token, no request`() {
        token = null
        assertEquals(ApiResult.Failed(ApiFailure.SessionDead), client.getCall(1))
        assertEquals(0, server.requestCount)
    }
}
