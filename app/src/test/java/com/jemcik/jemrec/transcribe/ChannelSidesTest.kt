package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The split-or-mix decision, on levels shaped like the A24's two test calls (research.md R4). */
class ChannelSidesTest {

    private val frames = 3000 // a minute of 20 ms frames
    private val quiet = -70.0

    /** A conversation taking turns: each side speaks for two seconds while the other is quiet. */
    private fun turns(): Pair<DoubleArray, DoubleArray> {
        val rnd = Random(1)
        val left = DoubleArray(frames)
        val right = DoubleArray(frames)
        for (i in 0 until frames) {
            val leftTalks = (i / 100) % 2 == 0
            val speech = -25.0 + rnd.nextDouble(-5.0, 5.0)
            left[i] = if (leftTalks) speech else quiet + rnd.nextDouble(0.0, 3.0)
            right[i] = if (leftTalks) quiet + rnd.nextDouble(0.0, 3.0) else speech
        }
        return left to right
    }

    @Test fun sidesTakingTurnsAreSplit() {
        val (l, r) = turns()
        assertEquals(Sides.SPLIT, ChannelSides.decide(l, r))
    }

    @Test fun oneMixCopiedTwiceIsMixed() {
        val (l, _) = turns()
        val r = DoubleArray(l.size) { l[it] + 0.01 } // Opus rounding, nothing more
        assertEquals(Sides.MIX, ChannelSides.decide(l, r))
    }

    /** The answering machine with the microphone muted: one side only. Still the sides apart. */
    @Test fun oneSilentSideIsSplit() {
        val (l, _) = turns()
        val r = DoubleArray(l.size) { quiet }
        assertEquals(Sides.SPLIT, ChannelSides.decide(r, l))
    }

    @Test fun tooLittleSoundToTellIsMixed() {
        val l = DoubleArray(frames) { if (it < 10) -20.0 else quiet }
        val r = DoubleArray(frames) { quiet }
        assertEquals(Sides.MIX, ChannelSides.decide(l, r))
    }

    @Test fun theUsersSettingWins() {
        val (l, r) = turns()
        assertEquals(Sides.MIX, ChannelSides.decide(l, r, Sides.MIX))
        assertEquals(Sides.SPLIT, ChannelSides.decide(l, l, Sides.SPLIT))
    }

    @Test fun aMonoFileReadsAsOneMix() {
        val pcm = ShortArray(48_000) { ((it % 100) * 300).toShort() }
        val (l, r) = ChannelSides.levels(pcm, pcm.size, channels = 1, sampleRate = 48_000)
        assertEquals(50, l.size)
        assertEquals(Sides.MIX, ChannelSides.decide(l, r))
    }

    @Test fun levelsReadEachChannelOfInterleavedStereo() {
        val pcm = ShortArray(48_000 * 2) { if (it % 2 == 0) 16_384 else 0 }
        val (l, r) = ChannelSides.levels(pcm, pcm.size, channels = 2, sampleRate = 48_000)
        assertEquals(-6.0, l[10], 0.1)
        assertEquals(true, r[10] < -150)
    }
}
