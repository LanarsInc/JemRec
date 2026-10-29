package com.jemcik.jemrec.transcripts

import com.jemcik.jemrec.transcripts.db.TranscriptSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What is typed, as the full-text query; and one snippet per recording. */
class TranscriptSearchTest {

    @Test fun eachWordIsAQuotedPrefixAndAllAreRequired() {
        assertEquals("\"lever*\" \"fredag*\"", TranscriptSearch.matchQuery("lever  fredag"))
    }

    /** FTS syntax typed by accident is matched as text, never parsed. */
    @Test fun syntaxIsNotSyntax() {
        assertEquals("\"NEAR*\" \"OR*\" \"ab*\"", TranscriptSearch.matchQuery("NEAR OR \"ab-c*"))
    }

    @Test fun cyrillicAndNorwegianLettersAreWords() {
        assertEquals("\"доставка*\" \"møtes*\"", TranscriptSearch.matchQuery("доставка, møtes!"))
    }

    @Test fun nothingToLookForIsNull() {
        assertNull(TranscriptSearch.matchQuery("  - ! "))
        assertNull(TranscriptSearch.matchQuery("a"))
    }

    @Test fun theFirstLineSaidWinsAndSummariesFillIn() {
        val lines = listOf(TranscriptSearchHit("a", 9_000, "later"), TranscriptSearchHit("a", 1_000, "first"))
        val summaries = listOf(TranscriptSearchHit("a", 0, "summary"), TranscriptSearchHit("b", 0, "only summary"))
        assertEquals(mapOf("a" to "first", "b" to "only summary"), TranscriptSearch.merge(lines, summaries))
    }
}
