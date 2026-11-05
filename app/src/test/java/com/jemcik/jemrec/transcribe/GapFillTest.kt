package com.jemcik.jemrec.transcribe

import com.jemcik.jemrec.transcribe.SpeechGapSnap.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speech heard with no line written for it: found, and its lines put in their place. */
class GapFillTest {

    /** A line said at a natural pace: [text], then a word a second, so it is not taken as sparse. */
    private fun line(from: Long, to: Long, text: String = "x") =
        TranscriptSegment(from, to, (listOf(text) + List(((to - from) / 1_000).toInt()) { "and" }).joinToString(" "))

    private fun first(lines: List<TranscriptSegment>) = lines.map { it.text.substringBefore(' ') }

    /** LANARS's call, 2026-11-05: the answer at 2:52-3:10 had speech and no line - two stretches, heard as one. */
    @Test fun speechWithNoLineIsAGap() {
        val speech = listOf(Speech(160_000, 172_000), Speech(173_900, 183_500), Speech(185_200, 191_200))
        val lines = listOf(line(162_480, 171_880, "one"), line(190_270, 196_130, "two"))
        assertEquals(listOf((171_880L - GapFill.PAD_MS)..(190_270L + GapFill.PAD_MS)), GapFill.gaps(speech, lines, 274_000))
    }

    /** The same call: a loop over 2:52-3:10, taken out later, hid that nothing true was written there. */
    @Test fun aLoopDoesNotCoverSpeech() {
        val speech = listOf(Speech(10_000, 20_000))
        val said = "I was just wondering if you were going to say something."
        val loop = listOf(TranscriptSegment(10_000, 14_000, said), TranscriptSegment(14_000, 20_000, said))
        assertEquals(listOf((14_000L - GapFill.PAD_MS)..(20_000L + GapFill.PAD_MS)), GapFill.gaps(speech, loop, 30_000))
    }

    /** A 30 s line of 6 words at 1:10-1:40 (English over Russian) covers nothing, and goes when heard again. */
    @Test fun aSparseLineIsNotTrusted() {
        val words = listOf(Word(70_200, "you"), Word(71_000, "answer"), Word(82_000, "English"), Word(96_000, "d"), Word(97_000, "e"), Word(98_000, "f"))
        val sparse = TranscriptSegment(70_200, 99_800, "you answer English d e f", words = words)
        assertTrue(GapFill.sparse(sparse))
        val gaps = GapFill.gaps(listOf(Speech(67_420, 95_120)), listOf(sparse), 274_000)
        assertEquals(listOf((67_420L - GapFill.PAD_MS)..(95_120L + GapFill.PAD_MS)), gaps)
        val merged = GapFill.merge(listOf(sparse), gaps.single(), listOf(line(0, 20_000, "what was said")))
        assertEquals(listOf("what"), first(merged))
    }

    /** A line whose words fill it is cut around a gap, its words before and after kept in order. */
    @Test fun aFullLineIsCutAroundAGap() {
        val words = (0 until 8).map { Word(10_000L + it * 1_000, "w$it") }
        val full = TranscriptSegment(10_000, 18_000, words.joinToString(" ") { it.text }, words = words)
        val merged = GapFill.merge(listOf(full), 12_500L..15_500L, listOf(line(0, 2_000, "heard")))
        assertEquals(listOf("w0 w1 w2", "heard and and", "w6 w7"), merged.map { it.text })
    }

    /** A second round hears what the first left unwritten, never the same gap twice. */
    @Test fun aGapHeardIsNotHeardAgain() {
        val first = 48_500L..100_300L
        val remainder = 94_600L..100_300L
        assertEquals(listOf(remainder), GapFill.again(listOf(first, remainder), listOf(first)))
        assertEquals(emptyList<LongRange>(), GapFill.again(listOf(first.first + 200..first.last - 300), listOf(first)))
    }

    @Test fun aShortGapOrNoVadIsNone() {
        assertEquals(emptyList<LongRange>(), GapFill.gaps(listOf(Speech(1_000, 3_500)), emptyList(), 10_000))
        assertEquals(emptyList<LongRange>(), GapFill.gaps(emptyList(), emptyList(), 10_000))
    }

    @Test fun theLongestGapsAreTakenAndGivenBackInOrder() {
        val speech = (0 until 10).map { Speech(it * 20_000L, it * 20_000L + 4_000L + it * 100L) }
        val gaps = GapFill.gaps(speech, emptyList(), 300_000)
        assertEquals(GapFill.MAX_GAPS, gaps.size)
        assertEquals(gaps.sortedBy { it.first }, gaps)
        assertTrue(gaps.first().first >= 80_000 - GapFill.PAD_MS)
    }

    @Test fun linesFoundGoInTheGapsPlaceAndRepeatNothing() {
        val lines = listOf(line(0, 5_000, "before"), line(20_000, 25_000, "after"))
        val gap = 4_700L..20_300L
        val found = listOf(line(0, 300, "edge"), line(1_000, 8_000, "found"), line(15_800, 16_000, "past the gap"))
        val merged = GapFill.merge(lines, gap, found)
        // "edge" fell inside "before" (the padding heard its end again); "past the gap" is after it.
        assertEquals(listOf("before", "found", "after"), first(merged))
        assertEquals(5_700L, merged[1].startMs)
        // A line the gap's padding heard inside an existing line is dropped as a repeat.
        val repeat = GapFill.merge(lines, 3_000L..9_000L, listOf(TranscriptSegment(100, 1_500, "before again")))
        assertEquals(listOf("before", "after"), first(repeat))
    }
}
