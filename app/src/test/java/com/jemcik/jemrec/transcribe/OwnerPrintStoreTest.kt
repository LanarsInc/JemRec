package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OwnerPrintStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val you = floatArrayOf(1f, 0f, 0f)
    private val youAgain = floatArrayOf(0f, 1f, 0f)

    private fun store(model: String = "voices-a") = OwnerPrintStore(File(tmp.root, "voice/print.txt"), model)

    @Test fun nothingKeptIsNoPrint() = assertNull(store().read())

    @Test fun aSampleIsKeptAsIs() {
        store().saveSample(you, now = 7)
        val p = store().read()!!
        assertEquals(PrintSource.SAMPLE, p.source)
        assertEquals(7L, p.madeAt)
        assertArrayEquals(you, p.vector, 0f)
    }

    @Test fun callsBuildAPrintOfTheirMean() {
        store().addCall(you, now = 1)
        val p = store().addCall(youAgain, now = 2)!!
        assertEquals(PrintSource.CALLS, p.source)
        assertEquals(2, store().read()!!.callCount)
        assertEquals(0.7071, VoiceLink.cosine(store().read()!!.vector, you), 1e-3)
    }

    /** Decided 2026-11-03: a recorded sample first; calls never replace it. */
    @Test fun callsNeverReplaceASample() {
        store().saveSample(you, now = 1)
        store().addCall(youAgain, now = 2)
        assertEquals(PrintSource.SAMPLE, store().read()!!.source)
        assertArrayEquals(you, store().read()!!.vector, 0f)
    }

    @Test fun aSampleReplacesPrintsFromCalls() {
        store().addCall(youAgain, now = 1)
        store().saveSample(you, now = 2)
        val p = store().read()!!
        assertEquals(PrintSource.SAMPLE, p.source)
        assertEquals(0, p.callCount)
    }

    /** Another voice model's numbers mean nothing to this one. */
    @Test fun aPrintOfAnotherModelIsVoid() {
        store("voices-a").saveSample(you, now = 1)
        assertNull(store("voices-b").read())
    }

    @Test fun deleteLeavesNothing() {
        store().saveSample(you, now = 1)
        store().delete()
        assertNull(store().read())
        assertFalse(File(tmp.root, "voice/print.txt").exists())
    }

    @Test fun anUnreadableFileIsNoPrint() {
        File(tmp.root, "voice").mkdirs()
        File(tmp.root, "voice/print.txt").writeText("version=1\nmodel=voices-a\nsource=SAMPLE\nmadeAt=x\n")
        assertNull(store().read())
    }
}
