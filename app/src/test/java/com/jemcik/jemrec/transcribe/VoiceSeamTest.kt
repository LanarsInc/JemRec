package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSeamTest {

    private fun line(from: Long, to: Long, vararg words: Pair<Long, String>) =
        TranscriptSegment(from, to, words.joinToString(" ") { it.second }, Speaker.NONE, words.map { Word(it.first, it.second) })

    private fun apply(lines: List<TranscriptSegment>, vararg turns: VoiceTurn) =
        VoiceSeam.apply(lines, turns.toList(), Speaker.VOICE1, Speaker.VOICE2)

    @Test fun aLineTakesTheVoiceThatCoversMostOfIt() {
        val out = apply(
            listOf(line(0, 3_000, 0L to "Hello", 1_000L to "there")),
            VoiceTurn(0, 2_500, 1), VoiceTurn(2_500, 3_500, 0),
        )
        assertEquals(listOf(Speaker.VOICE2), out.map { it.speaker })
        assertEquals("Hello there", out.single().text)
    }

    /** R16's 11:44 case: a line holding both people's words is cut where the voice changes. */
    @Test fun aSharedLineIsCutAtTheTurnWithEveryWordOnce() {
        val shared = line(
            10_000, 16_000,
            10_000L to "Can", 10_400L to "you", 10_800L to "come", 11_300L to "Friday?",
            13_200L to "Yes,", 13_700L to "at", 14_000L to "ten.",
        )
        val out = apply(listOf(shared), VoiceTurn(9_000, 12_800, 0), VoiceTurn(12_800, 17_000, 1))
        assertEquals(listOf("Can you come Friday?", "Yes, at ten."), out.map { it.text })
        assertEquals(listOf(Speaker.VOICE1, Speaker.VOICE2), out.map { it.speaker })
        assertEquals(listOf(10_000L to 13_200L, 13_200L to 16_000L), out.map { it.startMs to it.endMs })
        assertEquals(shared.words, out.flatMap { it.words })
    }

    /** Under a second of the other voice is the turn's timing, not a turn. */
    @Test fun aSlipOfTheOtherVoiceDoesNotCut() {
        val out = apply(
            listOf(line(0, 4_000, 0L to "We", 500L to "can", 1_000L to "do", 3_500L to "that.")),
            VoiceTurn(0, 3_400, 0), VoiceTurn(3_400, 4_000, 1),
        )
        assertEquals(1, out.size)
        assertEquals(Speaker.VOICE1, out.single().speaker)
    }

    /** A word of the other voice lasting under a second is folded into its run; a real turn after it is still cut. */
    @Test fun aShortRunJoinsItsNeighbour() {
        val out = apply(
            listOf(line(
                0, 6_000,
                0L to "one", 1_000L to "two", 2_000L to "three", 2_400L to "four", 3_000L to "five",
                4_600L to "six", 5_200L to "seven",
            )),
            VoiceTurn(0, 2_200, 0), VoiceTurn(2_200, 2_800, 1), VoiceTurn(2_800, 4_500, 0), VoiceTurn(4_500, 6_000, 1),
        )
        assertEquals(listOf("one two three four five", "six seven"), out.map { it.text })
        assertEquals(listOf(Speaker.VOICE1, Speaker.VOICE2), out.map { it.speaker })
    }

    @Test fun aLineWithoutWordTimesIsNeverCut() {
        val plain = TranscriptSegment(0, 6_000, "Both of us said this", Speaker.NONE)
        val out = apply(listOf(plain), VoiceTurn(0, 3_000, 0), VoiceTurn(3_000, 6_000, 1))
        assertEquals(listOf(plain.copy(speaker = Speaker.VOICE1)), out)
    }

    /** A line between turns - the diariser dropped it as too short - takes the nearest voice. */
    @Test fun aLineOutsideEveryTurnTakesTheNearest() {
        val out = apply(listOf(line(5_000, 5_500, 5_000L to "Mm.")), VoiceTurn(0, 4_000, 0), VoiceTurn(5_800, 9_000, 1))
        assertEquals(Speaker.VOICE2, out.single().speaker)
    }

    @Test fun noTurnsLeavesTheLinesAsTheyAre() {
        val lines = listOf(line(0, 1_000, 0L to "Hi"))
        assertEquals(lines, VoiceSeam.apply(lines, emptyList(), Speaker.VOICE1, Speaker.VOICE2))
    }

    /** The names are the caller's: You and Them when the owner's voice is known. */
    @Test fun theVoicesTakeTheNamesGiven() {
        val out = VoiceSeam.apply(listOf(line(0, 1_000, 0L to "Hi")), listOf(VoiceTurn(0, 1_000, 0)), Speaker.THEM, Speaker.YOU)
        assertEquals(Speaker.THEM, out.single().speaker)
    }
}
