package com.jemcik.jemrec.crm.api

import com.jemcik.jemrec.crm.CrmFixtures
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The bodies the app builds equal the API document's requests. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class BodiesTest {

    private fun assertSame(fixture: String, body: String) {
        val expected = CrmFixtures.json(fixture)
        assertTrue("$fixture\nexpected $expected\nbuilt    $body", CrmFixtures.same(expected, JSONObject(body)))
    }

    private fun facts(
        id: String, outgoing: Boolean, answered: Boolean, phone: String?, presentation: Presentation, country: String?,
        start: String, seconds: Int, outcome: Outcome, choice: DealChoice, dealId: Int? = null, byUser: Boolean? = null,
        next: NextCall? = null, transcript: Boolean,
    ) = CallFacts(id, outgoing, answered, phone, presentation, country, Instants.parse(start), seconds, outcome, choice, dealId, byUser, next, transcript)

    @Test
    fun `step 1, logged with a next call`() = assertSame(
        "call-request",
        Bodies.call(
            facts(
                "3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6c", true, true, "+47 400 00 001", Presentation.ALLOWED, "no",
                "2026-11-03T08:14:05.123Z", 184, Outcome.REACHED, DealChoice.DEAL, 1000456, false,
                NextCall("2026-11-05", "10:00", "Follow up offer"), true,
            ),
        ),
    )

    @Test
    fun `step 1, the document's other calls`() {
        assertSame(
            "call-pending-request",
            Bodies.call(facts("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", true, true, "+47 400 00 002", Presentation.ALLOWED, "no", "2026-11-03T08:05:00.000Z", 75, Outcome.REACHED, DealChoice.AUTO, transcript = true)),
        )
        assertSame(
            "call-missed-request",
            Bodies.call(facts("8e2f3a4b-5c6d-4e7f-9a81-223344556677", false, false, "40000001", Presentation.ALLOWED, "NO", "2026-11-03T10:00:00.000Z", 0, Outcome.NO_ANSWER, DealChoice.AUTO, transcript = false)),
        )
        assertSame(
            "call-hidden-request",
            Bodies.call(facts("7d1e2f3a-4b5c-4d6e-8f70-112233445566", false, true, "+4740000099", Presentation.RESTRICTED, null, "2026-11-03T09:00:00.000Z", 95, Outcome.REACHED, DealChoice.AUTO, transcript = true)),
        )
        assertSame(
            "call-declined-request",
            Bodies.call(facts("a04b5c6d-7e8f-4091-9ca3-445566778899", true, true, "+47 400 00 002", Presentation.ALLOWED, "no", "2026-11-03T11:00:00.000Z", 60, Outcome.REACHED, DealChoice.NONE, dealId = 1000456, transcript = true)),
        )
    }

    @Test
    fun `a missed incoming call never carries a next call, and a hidden one no number`() {
        val missed = JSONObject(Bodies.call(facts("8e2f3a4b-5c6d-4e7f-9a81-223344556677", false, false, "40000001", Presentation.ALLOWED, "NO", "2026-11-03T10:00:00.000Z", 12, Outcome.NO_ANSWER, DealChoice.AUTO, next = NextCall("2026-11-04"), transcript = false)))
        assertFalse(missed.has("next"))
        assertTrue(missed.getInt("duration_seconds") == 0)
    }

    @Test
    fun `pick, content and the rest`() {
        assertSame("deal-request", Bodies.pick(DealChoice.DEAL, 1000458, NextCall("2026-11-06")))
        assertSame("deal-none-request", Bodies.pick(DealChoice.NONE, null, NextCall("2026-11-06")))
        assertSame("content-failed-request", Bodies.failed())
        assertSame(
            "content-ready-request",
            Bodies.ready(
                Content(
                    "Customer wants a quote for a new website by Friday.\n• Decision maker: the CEO.",
                    "Seller: Hei, det er Test Seller fra LANARS.\nCustomer: Hei, vi trenger en ny nettside.",
                    "nb", false,
                ),
            ),
        )
        assertSame("lookup-request", Bodies.lookup("400 00 001", "no"))
        assertTrue(JSONObject(Bodies.acknowledge("2026-11-02")).getString("version") == "2026-11-02")
        val withoutNext = JSONObject(Bodies.withoutNext(CrmFixtures.text("call-request")))
        assertFalse(withoutNext.has("next"))
    }

    @Test
    fun `the exchange`() {
        val sent = JSONObject(Bodies.exchange("<Google ID token>", "0b7f6c1e-2d4a-4e8b-9f31-6a5c2e7d8b90", "Example Phone 12", "1.4.0", 35))
        assertTrue(CrmFixtures.same(CrmFixtures.json("session-request"), sent))
    }
}
