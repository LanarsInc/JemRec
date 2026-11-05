package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceSampleTest {

    private fun tone(ms: Int, amplitude: Float) = FloatArray(ms * 16) { (amplitude * sin(2 * PI * 200 * it / 16_000)).toFloat() }

    /** Pauses between sentences are not voice: only the frames with sound are kept. */
    @Test fun pausesAreLeftOut() {
        val pcm = tone(1_000, 0.2f) + FloatArray(16_000) + tone(500, 0.2f)
        val speech = VoiceSample.speechOnly(pcm)
        assertEquals(1_500L, VoiceSample.speechMs(speech))
    }

    @Test fun aRoomTooQuietIsNoSpeech() = assertEquals(0L, VoiceSample.speechMs(VoiceSample.speechOnly(tone(2_000, 0.001f))))

    @Test fun aFullSampleIsEnough() =
        assert(VoiceSample.speechMs(VoiceSample.speechOnly(tone(25_000, 0.1f))) >= VoiceSample.MIN_SPEECH_MS)

    /** The gap between two words is speech: it stays in, as it was heard. */
    @Test fun gapsBetweenWordsAreKept() {
        val pcm = tone(400, 0.2f) + FloatArray(200 * 16) + tone(400, 0.2f)
        assertEquals(1_000L, VoiceSample.speechMs(VoiceSample.speechOnly(pcm)))
    }

    /** Quiet at the start and the end is not between words: it is left out. */
    @Test fun quietAtTheEdgesIsLeftOut() {
        val pcm = FloatArray(200 * 16) + tone(400, 0.2f) + FloatArray(200 * 16)
        assertEquals(400L, VoiceSample.speechMs(VoiceSample.speechOnly(pcm)))
    }

    /**
     * A sample read at a speaking pace: loud stretches of 200 ms, 200 ms quieter between them,
     * a second's pause after each sentence of eight, for 30 s. Counting only the loud frames gave
     * the owner 14-15 s (2026-11-05) and a "too short"; here they come to under the bar on their
     * own, so it is the gaps between words that must carry it.
     */
    @Test fun aSampleReadWithPausesIsEnough() {
        val sentence = (1..8).fold(FloatArray(0)) { acc, _ -> acc + tone(200, 0.05f) + FloatArray(200 * 16) }
        var pcm = FloatArray(0)
        var sentences = 0
        while (pcm.size + sentence.size + 16_000 <= 30 * 16_000) { pcm += sentence + FloatArray(16_000); sentences++ }
        val loudOnly = sentences * 8 * 200L // what counting the loud frames alone gives
        val speech = VoiceSample.speechMs(VoiceSample.speechOnly(pcm))
        assert(loudOnly < VoiceSample.MIN_SPEECH_MS) { "the case is not hard enough: $loudOnly" }
        assert(speech >= VoiceSample.MIN_SPEECH_MS) { "only $speech ms of speech" }
    }
}
