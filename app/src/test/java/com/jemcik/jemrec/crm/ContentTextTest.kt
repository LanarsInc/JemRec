package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.db.WorkCallEntry
import com.jemcik.jemrec.summary.CallSummary
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2's texts (§8, research R8) and which of them go (FR-031, FR-035, FR-036). */
class ContentTextTest {

    private fun line(text: String, who: Speaker, at: Long = 0) = TranscriptSegment(at, at + 1000, text, who)

    @Test
    fun `turns as label colon text, no times, a side's lines joined`() {
        val text = ContentText.transcript(
            listOf(
                line("Hei, det er Test Seller fra LANARS.", Speaker.YOU),
                line("Hei,", Speaker.THEM),
                line("  vi trenger en ny nettside. ", Speaker.THEM),
                line("", Speaker.YOU),
            ),
        )
        assertEquals("Seller: Hei, det er Test Seller fra LANARS.\nCustomer: Hei, vi trenger en ny nettside.", text)
    }

    @Test
    fun `one mix - every line still has a label`() {
        assertEquals("Call: Hello there. How are you?", ContentText.transcript(listOf(line("Hello there.", Speaker.NONE), line("How are you?", Speaker.NONE))))
    }

    @Test
    fun `the summary in plain text with bullets, empty lists left out`() {
        val s = CallSummary(
            intent = "Customer wants a quote for a new website.",
            summary = "They need it by Friday.",
            keyPoints = listOf("Decision maker: the CEO."),
            decisions = emptyList(),
            actionItems = listOf("Send a quote by Thursday", " "),
            keyFacts = emptyList(),
        )
        assertEquals(
            "Customer wants a quote for a new website.\n\nThey need it by Friday.\n\nKey points:\n• Decision maker: the CEO.\n\nAction items:\n• Send a quote by Thursday",
            ContentText.summary(s),
        )
    }

    @Test
    fun `language codes as the CRM takes them`() {
        assertEquals("nb", ContentText.language("no"))
        assertEquals("en", ContentText.language("EN"))
        assertEquals("uk", ContentText.language("uk"))
        assertEquals("und", ContentText.language("auto"))
        assertEquals("und", ContentText.language(null))
    }

    @Test
    fun `cut at a line's end, or never inside a surrogate pair`() {
        val lines = (1..10).joinToString("\n") { "x".repeat(9) }
        val (cut, truncated) = ContentText.cut(lines, 25)
        assertTrue(truncated)
        assertEquals("xxxxxxxxx\nxxxxxxxxx", cut)
        val emoji = "a".repeat(9) + "😀" + "b".repeat(5000)
        val (cut2, _) = ContentText.cut(emoji, 10) // 10 falls between the pair's halves
        assertEquals("a".repeat(9), cut2)
        assertFalse(ContentText.cut("short", 10).second)
    }

    private fun call(content: Content) = WorkCallEntry(
        clientCallId = "c", callLogId = 1, userId = 1, outgoing = true, answered = true, phone = "1", presentation = "ALLOWED",
        countryIso = null, startedAtMs = 0, durationSeconds = 1, endedAtMs = 1, recording = "r.ogg", outcome = "REACHED",
        localState = "SENT", dueAtMs = 0, contentState = content.name, serverId = 5,
    )

    @Test
    fun `only the first texts go, and failed may be followed once by them`() {
        assertEquals(Content.READY_TO_SEND.name, ContentHook.ready(call(Content.EXPECTED), "{}")!!.contentState)
        assertEquals(Content.READY_TO_SEND.name, ContentHook.ready(call(Content.SENT_FAILED), "{}")!!.contentState)
        assertNull(ContentHook.ready(call(Content.SENT_READY), "{}"))
        assertNull("texts on their way are not replaced", ContentHook.ready(call(Content.READY_TO_SEND), "{}"))
    }

    @Test
    fun `failed only while the texts are still awaited`() {
        assertEquals(Content.FAILED_TO_SEND.name, ContentHook.failed(call(Content.EXPECTED))!!.contentState)
        assertNull(ContentHook.failed(call(Content.READY_TO_SEND)))
        assertNull(ContentHook.failed(call(Content.SENT_READY)))
        assertNull(ContentHook.failed(call(Content.NOT_EXPECTED)))
    }
}
