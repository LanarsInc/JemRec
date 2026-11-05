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
}
