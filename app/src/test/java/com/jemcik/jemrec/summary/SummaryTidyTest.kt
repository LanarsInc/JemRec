package com.jemcik.jemrec.summary

import org.junit.Assert.assertEquals
import org.junit.Test

/** What Qwen3 1.7B put in the A24's summaries that was not summary (research.md R14). */
class SummaryTidyTest {

    private val lines = listOf(
        SummaryLine(0, 4_000, "Hei, jeg ringer om leveringen av kjøleskapet.", "You"),
        SummaryLine(4_000, 8_000, "Ja, jeg har bestillingen her. Det er til Storgata 14?", "Lars"),
    )

    private fun summary(points: List<String> = emptyList(), facts: List<String> = emptyList()) =
        CallSummary("levering", "Du ringer om leveringen.", points, emptyList(), emptyList(), facts)

    @Test fun bareTimestampsGo() {
        val tidy = SummaryTidy.apply(summary(facts = listOf("0:00", "[0:04]", "Storgata 14")), lines)
        assertEquals(listOf("Storgata 14"), tidy.keyFacts)
    }

    @Test fun aLineCopiedOutWithItsSpeakerGoes() {
        val tidy = SummaryTidy.apply(
            summary(points = listOf("[0:04] Lars: Ja, jeg har bestillingen her. Det er til Storgata 14?", "[0:04] Leveringen går til Storgata 14")),
            lines,
        )
        assertEquals(listOf("[0:04] Leveringen går til Storgata 14"), tidy.keyPoints)
    }

    @Test fun aCopyDiffersOnlyInCaseAndTheFullStop() {
        val tidy = SummaryTidy.apply(summary(points = listOf("You: hei, jeg ringer om leveringen av kjøleskapet")), lines)
        assertEquals(emptyList<String>(), tidy.keyPoints)
    }

    @Test fun theModelsOwnWordsStay() {
        val points = listOf("[0:00] Du ringer om kjøleskapet", "Prisen er tolv tusen")
        assertEquals(points, SummaryTidy.apply(summary(points = points), lines).keyPoints)
    }
}
