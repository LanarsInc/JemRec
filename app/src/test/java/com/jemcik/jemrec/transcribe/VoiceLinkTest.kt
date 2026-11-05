package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLinkTest {

    // Two people's voices as embeddings: far apart (cosine 0), and a little noise on each.
    private val ann = floatArrayOf(1f, 0f, 0f)
    private val bob = floatArrayOf(0f, 1f, 0f)
    private val annAgain = floatArrayOf(0.95f, 0.05f, 0.3f)
    private val bobAgain = floatArrayOf(0.1f, 0.9f, 0.4f)

    private fun turn(from: Long, to: Long, voice: Int) = VoiceTurn(from, to, voice)

    @Test fun oneChunkPassesThroughWithTheFirstVoiceHeardAsVoice1() {
        val call = VoiceLink.link(listOf(VoiceLink.Chunk(listOf(turn(0, 2_000, 1), turn(2_000, 5_000, 0)), mapOf(0 to ann, 1 to bob))))
        assertEquals(listOf(turn(0, 2_000, 0), turn(2_000, 5_000, 1)), call.turns)
        assertEquals(1.0, VoiceLink.cosine(call.embeddings[0], bob), 1e-6)
        assertEquals(1.0, VoiceLink.cosine(call.embeddings[1], ann), 1e-6)
    }

    /** The next chunk numbered them the other way round: they are matched by voice, not by number. */
    @Test fun chunksAreLinkedByTheirVoicesNotTheirNumbers() {
        val call = VoiceLink.link(listOf(
            VoiceLink.Chunk(listOf(turn(0, 3_000, 0), turn(3_000, 6_000, 1)), mapOf(0 to ann, 1 to bob)),
            VoiceLink.Chunk(listOf(turn(300_000, 303_000, 0), turn(303_000, 306_000, 1)), mapOf(0 to bobAgain, 1 to annAgain)),
        ))
        assertEquals(listOf(0, 1, 1, 0), call.turns.map { it.voice })
    }

    /** A chunk where only one person spoke joins the nearer voice of the two already known. */
    @Test fun aChunkWithOneVoiceJoinsTheNearer() {
        val call = VoiceLink.link(listOf(
            VoiceLink.Chunk(listOf(turn(0, 3_000, 0), turn(3_000, 6_000, 1)), mapOf(0 to ann, 1 to bob)),
            VoiceLink.Chunk(listOf(turn(300_000, 310_000, 0)), mapOf(0 to bobAgain)),
        ))
        assertEquals(listOf(0, 1, 1), call.turns.map { it.voice })
    }

    /** One person in the first chunk, then someone unlike them: the second voice. */
    @Test fun aNewVoiceAfterAOneVoiceChunkIsTheSecond() {
        val call = VoiceLink.link(listOf(
            VoiceLink.Chunk(listOf(turn(0, 10_000, 0)), mapOf(0 to ann)),
            VoiceLink.Chunk(listOf(turn(300_000, 310_000, 0)), mapOf(0 to bob)),
            VoiceLink.Chunk(listOf(turn(600_000, 610_000, 1)), mapOf(1 to annAgain)),
        ))
        assertEquals(listOf(0, 1, 0), call.turns.map { it.voice })
        assertEquals(2, call.embeddings.size)
    }

    /** A voice's embedding over the call is weighted by how much it spoke. */
    @Test fun embeddingsAreWeightedBySpeech() {
        val call = VoiceLink.link(listOf(
            VoiceLink.Chunk(listOf(turn(0, 9_000, 0), turn(9_000, 10_000, 1)), mapOf(0 to ann, 1 to bob)),
            VoiceLink.Chunk(listOf(turn(300_000, 301_000, 0), turn(301_000, 310_000, 1)), mapOf(0 to annAgain, 1 to bobAgain)),
        ))
        assertEquals(10_000L, call.speechMs(0))
        assertEquals(10_000L, call.speechMs(1))
        // Mostly the first chunk's "ann".
        assert(VoiceLink.cosine(call.embeddings[0], ann) > VoiceLink.cosine(call.embeddings[0], annAgain))
    }

    /**
     * One person, but each chunk told apart into two of their voices (sherpa-onnx is told there are
     * two): one call voice, not two. The A24's 306 s call in 60 s chunks, 2026-11-03.
     */
    @Test fun twoVoicesOfOnePersonInAChunkAreOne() {
        val annLow = floatArrayOf(0.9f, 0.1f, 0.2f)
        val call = VoiceLink.link(listOf(
            VoiceLink.Chunk(listOf(turn(0, 30_000, 0), turn(30_000, 60_000, 1)), mapOf(0 to ann, 1 to annLow)),
            VoiceLink.Chunk(listOf(turn(60_000, 90_000, 1), turn(90_000, 120_000, 0)), mapOf(0 to annAgain, 1 to annLow)),
        ))
        assertEquals(1, call.embeddings.size)
        assertEquals(setOf(0), call.turns.map { it.voice }.toSet())
    }

    /** ...while a real second person after such a chunk is still the second voice. */
    @Test fun aRealSecondPersonAfterAOnePersonChunkIsTheSecondVoice() {
        val annLow = floatArrayOf(0.9f, 0.1f, 0.2f)
        val call = VoiceLink.link(listOf(
            VoiceLink.Chunk(listOf(turn(0, 30_000, 0), turn(30_000, 60_000, 1)), mapOf(0 to ann, 1 to annLow)),
            VoiceLink.Chunk(listOf(turn(60_000, 90_000, 0), turn(90_000, 120_000, 1)), mapOf(0 to annAgain, 1 to bob)),
        ))
        assertEquals(2, call.embeddings.size)
        assertEquals(listOf(0, 0, 0, 1), call.turns.map { it.voice })
    }

    @Test fun nothingInNothingOut() {
        assertEquals(VoiceLink.Call(emptyList(), emptyList()), VoiceLink.link(emptyList()).let { VoiceLink.Call(it.turns, it.embeddings) })
        assertEquals(0, VoiceLink.link(listOf(VoiceLink.Chunk(emptyList(), emptyMap()))).embeddings.size)
    }
}
