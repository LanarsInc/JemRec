package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSilenceTest {

    private val ann = floatArrayOf(1f, 0f)
    private val bob = floatArrayOf(0f, 1f)
    private val annOnABadLine = floatArrayOf(0.9f, 0.3f)

    private fun call(firstMs: Long, secondMs: Long, second: FloatArray = bob) = VoiceLink.Call(
        listOf(VoiceTurn(0, firstMs, 0), VoiceTurn(firstMs, firstMs + secondMs, 1)),
        listOf(ann, second),
    )

    @Test fun aConversationIsTwoVoices() = assertTrue(VoiceSilence.twoVoices(call(20_000, 15_000)))

    /** R16's 14:46 call: 83 s against 22 s of speech is still two people. */
    @Test fun anUnevenConversationIsTwoVoices() = assertTrue(VoiceSilence.twoVoices(call(83_000, 22_000)))

    @Test fun tooLittleSpeechIsNotNamed() = assertFalse(VoiceSilence.twoVoices(call(6_000, 3_000)))

    /** A voice menu with one "yes" in it: 97% one voice. */
    @Test fun oneVoiceWithATraceOfAnotherIsNotNamed() = assertFalse(VoiceSilence.twoVoices(call(97_000, 3_000)))

    /** One person split in two by a bad line: their halves sound alike. */
    @Test fun twoHalvesOfOneVoiceAreNotNamed() = assertFalse(VoiceSilence.twoVoices(call(20_000, 15_000, second = annOnABadLine)))

    @Test fun oneVoiceFoundIsNotNamed() =
        assertFalse(VoiceSilence.twoVoices(VoiceLink.Call(listOf(VoiceTurn(0, 60_000, 0)), listOf(ann))))
}
