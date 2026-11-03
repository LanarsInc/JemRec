package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnMergeTest {

    private fun line(ms: Long, text: String, who: Speaker) = TranscriptSegment(ms, ms + 500, text, who)

    @Test fun linesFromBothSidesFollowTheirTimes() {
        val you = listOf(line(0, "Hei", Speaker.YOU), line(4000, "Fint", Speaker.YOU))
        val them = listOf(line(2000, "Hallo", Speaker.THEM))
        assertEquals(listOf("Hei", "Hallo", "Fint"), TurnMerge.merge(you, them).map { it.text })
    }

    @Test fun whenBothStartTogetherYouComeFirst() {
        val merged = TurnMerge.merge(listOf(line(1000, "b", Speaker.THEM)), listOf(line(1000, "a", Speaker.YOU)))
        assertEquals(listOf(Speaker.YOU, Speaker.THEM), merged.map { it.speaker })
    }

    @Test fun emptyLinesGo() {
        assertEquals(1, TurnMerge.merge(listOf(line(0, " ", Speaker.YOU), line(1, "ja", Speaker.YOU))).size)
    }
}
