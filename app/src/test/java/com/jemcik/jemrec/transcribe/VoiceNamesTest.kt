package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** R16's cosines (A24, 2026-10-30) as fixtures: a print, and voices at a chosen cosine to it. */
class VoiceNamesTest {

    private val print = floatArrayOf(1f, 0f)

    /** A unit vector at cosine [c] to [print]. */
    private fun at(c: Double) = floatArrayOf(c.toFloat(), sqrt(1 - c * c).toFloat())

    private val voices = Speaker.VOICE1 to Speaker.VOICE2

    @Test fun theOwnerClearlyFirstIsYouThenThem() =
        assertEquals(Speaker.YOU to Speaker.THEM, VoiceNames.decide(listOf(at(0.64), at(0.17)), print))

    @Test fun theOwnerSecondIsTheSecondVoice() =
        assertEquals(Speaker.THEM to Speaker.YOU, VoiceNames.decide(listOf(at(0.10), at(0.53)), print))

    /** R16's 11:44: two strangers, 0.27 each - nobody here is the owner. */
    @Test fun twoStrangersStayVoices() = assertEquals(voices, VoiceNames.decide(listOf(at(0.27), at(0.27)), print))

    @Test fun aWeakMatchStaysVoices() = assertEquals(voices, VoiceNames.decide(listOf(at(0.40), at(0.05)), print))

    /** Both alike to the print: a near tie is not a name. */
    @Test fun aNearTieStaysVoices() = assertEquals(voices, VoiceNames.decide(listOf(at(0.55), at(0.40)), print))

    @Test fun noPrintStaysVoices() = assertEquals(voices, VoiceNames.decide(listOf(at(0.9), at(0.1)), null))

    @Test fun oneVoiceStaysVoices() = assertEquals(voices, VoiceNames.decide(listOf(at(0.9)), print))
}
