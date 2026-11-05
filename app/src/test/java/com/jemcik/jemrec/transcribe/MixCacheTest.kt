package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MixCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "voice-mix")
    private fun cache() = MixCache(dir)

    @Test fun chunksComeBackInOrderWithTheirTimes() {
        val c = cache()
        c.start("call-a")
        c.put(1, startMs = 290_000, keepFromMs = 300_000, audio = FloatArray(16) { 0.25f })
        c.put(0, startMs = 0, keepFromMs = 0, audio = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f))
        val pieces = c.pieces("call-a")!!
        assertEquals(listOf(0L to 0L, 290_000L to 300_000L), pieces.map { it.startMs to it.keepFromMs })
        val back = c.read(pieces[0])
        // 16-bit, so within one step of a 16-bit sample.
        floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f).forEachIndexed { i, x -> assertEquals(x, back[i], 1f / 32767) }
        assertEquals(16, c.read(pieces[1]).size)
    }

    /** Another call's audio is never handed out for this one. */
    @Test fun anotherCallsCacheIsNotThisOnes() {
        val c = cache()
        c.start("call-a")
        c.put(0, 0, 0, FloatArray(4))
        assertNull(c.pieces("call-b"))
    }

    @Test fun startingAnotherCallDropsTheLast() {
        val c = cache()
        c.start("call-a")
        c.put(0, 0, 0, FloatArray(4))
        c.start("call-b")
        assertNull(c.pieces("call-b"))
    }

    @Test fun nothingIsWrittenWithoutAStart() {
        cache().put(0, 0, 0, FloatArray(4))
        assertNull(cache().pieces("call-a"))
    }

    @Test fun clearLeavesNothing() {
        val c = cache()
        c.start("call-a")
        c.put(0, 0, 0, FloatArray(4))
        c.clear()
        assertFalse(dir.exists())
    }
}
