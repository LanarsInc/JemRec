package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WindowVoicesTest {

    private fun turn(from: Long, to: Long, voice: Int = 0) = VoiceTurn(from, to, voice)

    /** 2026-11-05, A24: the engine gave 204.6 s and 0.4 s; the second too short to describe. */
    @Test fun oneVoiceDescribedIsNeeded() =
        assertTrue(WindowVoices.needed(listOf(turn(0, 204_600), turn(205_000, 205_400, 1)), setOf(0)))

    @Test fun twoVoicesSharingIsNotNeeded() =
        assertFalse(WindowVoices.needed(listOf(turn(0, 50_000), turn(50_000, 90_000, 1)), setOf(0, 1)))

    /** Both described, but one holds over 95%: the engine left one person, as VoiceSilence would say. */
    @Test fun oneVoiceHoldingAlmostAllIsNeeded() =
        assertTrue(WindowVoices.needed(listOf(turn(0, 98_000), turn(98_000, 100_000, 1)), setOf(0, 1)))

    @Test fun noSpeechIsNotNeeded() = assertFalse(WindowVoices.needed(emptyList(), emptySet()))

    @Test fun windowsStepThroughATurnAndEndAtItsEnd() =
        assertEquals(
            listOf(0L until 2_000L, 1_000L until 3_000L, 2_000L until 4_000L, 2_500L until 4_500L),
            WindowVoices.windows(listOf(turn(0, 4_500))),
        )

    @Test fun aShortTurnIsOneWindowAndATinyOneNone() =
        assertEquals(listOf(10_000L until 11_500L), WindowVoices.windows(listOf(turn(10_000, 11_500), turn(20_000, 20_600))))

    /** Windows never cross a pause: the two turns are windowed apart. */
    @Test fun windowsStayWithinTurns() {
        val windows = WindowVoices.windows(listOf(turn(0, 3_000), turn(5_000, 8_000, 1)))
        assertTrue(windows.all { it.last < 3_000 || it.first >= 5_000 })
    }

    @Test fun fewerThanTwoVectorsAreNotSplit() = assertNull(WindowVoices.split(listOf(floatArrayOf(1f, 0f))))

    /** Two people, each window near one of two directions: the groups are the people. */
    @Test fun twoPeopleFallIntoTwoGroups() {
        val random = Random(7)
        val a = FloatArray(16) { random.nextFloat() - 0.5f }
        val b = FloatArray(16) { random.nextFloat() - 0.5f }
        fun near(v: FloatArray) = FloatArray(v.size) { v[it] + (random.nextFloat() - 0.5f) * 0.2f }
        val who = intArrayOf(0, 0, 1, 1, 0, 1, 1, 1, 0, 0, 1, 0)
        val groups = WindowVoices.split(who.map { if (it == 0) near(a) else near(b) })!!
        val same = groups.indices.all { groups[it] == groups[0] == (who[it] == who[0]) }
        assertTrue("groups ${groups.joinToString("")} for ${who.joinToString("")}", same)
    }

    /** Each window holds the speech halfway to its neighbours; one group's neighbours join. */
    @Test fun turnsFollowTheWindowsGroups() {
        val turns = listOf(turn(0, 4_000))
        val windows = WindowVoices.windows(turns)
        assertEquals(3, windows.size) // 0-2, 1-3, 2-4 s: centres 1, 2, 3 s
        assertEquals(
            listOf(turn(0, 1_500, 0), turn(1_500, 4_000, 1)),
            WindowVoices.turns(turns, windows, intArrayOf(0, 1, 1)),
        )
    }

    /** A turn too short for a window goes with the nearest window; nothing of the speech is lost. */
    @Test fun aShortTurnTakesTheNearestWindowsGroup() {
        val turns = listOf(turn(0, 2_000), turn(2_300, 2_800, 1), turn(10_000, 12_000))
        val windows = WindowVoices.windows(turns)
        val out = WindowVoices.turns(turns, windows, intArrayOf(1, 0))
        assertEquals(listOf(turn(0, 2_000, 1), turn(2_300, 2_800, 1), turn(10_000, 12_000, 0)), out)
        assertEquals(turns.sumOf { it.lengthMs }, out.sumOf { it.lengthMs })
    }

    @Test fun groupsMustMatchWindows() {
        val turns = listOf(turn(0, 4_000))
        assertTrue(runCatching { WindowVoices.turns(turns, WindowVoices.windows(turns), intArrayOf(0)) }.isFailure)
    }
}
