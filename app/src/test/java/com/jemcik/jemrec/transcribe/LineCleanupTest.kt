package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

/** The three cleanups, on lines shaped like the A24's test calls (2026-10-29). */
class LineCleanupTest {

    private fun you(from: Long, to: Long, text: String) = TranscriptSegment(from, to, text, Speaker.YOU)
    private fun them(from: Long, to: Long, text: String) = TranscriptSegment(from, to, text, Speaker.THEM)

    /** Their sentence on speakerphone, heard again by your microphone, worse: yours goes. */
    @Test fun anEchoOfTheirWordsOnYourSideGoes() {
        val lines = listOf(
            you(79_800, 103_800, "Så det var veldig vittig for norske at sanene skulle føle seg norske, være norske, snakk i norsk."),
            them(80_100, 85_800, "Det er veldig viktig for Norge at sanene skulle føle seg norske,"),
            them(85_800, 89_000, "hver norske snakke norsk."),
        )
        assertEquals(listOf(Speaker.THEM, Speaker.THEM), LineCleanup.apply(lines).map { it.speaker })
    }

    /** You talking while they are quiet, or saying something else over them, stays. */
    @Test fun yourOwnWordsStay() {
        val lines = listOf(
            you(0, 23_500, "For norskningspolitikken var altså en politikk av Norge mot det samiske folk."),
            you(30_000, 34_000, "Kan du gjenta det, takk?"),
            them(30_500, 34_000, "Leveransen kommer på fredag mellom ni og elleve."),
        )
        assertEquals(3, LineCleanup.apply(lines).size)
    }

    /** A short answer that repeats theirs is an answer, not an echo. */
    @Test fun aShortRepeatIsAnAnswer() {
        val lines = listOf(them(0, 2_000, "Fredag?"), you(1_500, 2_500, "Fredag."))
        assertEquals(2, LineCleanup.apply(lines).size)
    }

    @Test fun aLoopOnOneSideComesOutOnce() {
        val lines = listOf(
            you(31_900, 42_900, "I'm going to the pool."),
            you(42_900, 45_100, "I'm going to the pool."),
            them(43_000, 44_000, "We have warned."),
            you(70_300, 80_500, "I'm going to the pool."),
            you(80_500, 84_500, "The pool is all that if you want it to be right."),
        )
        assertEquals(
            listOf("I'm going to the pool.", "We have warned.", "The pool is all that if you want it to be right."),
            LineCleanup.apply(lines).map { it.text },
        )
    }

    @Test fun whispersNotesOnTheSoundGo() {
        val lines = listOf(them(16_800, 71_900, "[inaudible]"), them(72_000, 73_000, "(music)"), you(0, 1_000, "Hello [laughs] there"))
        assertEquals(listOf("Hello [laughs] there"), LineCleanup.apply(lines).map { it.text })
    }
}
