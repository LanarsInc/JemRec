package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietSideTest {

    private fun levels(db: Double) = DoubleArray(500) { db }
    private fun line(who: Speaker) = TranscriptSegment(1_000, 3_000, "words", who)

    /** Their channel silent while you talk: a line for them is nobody's. */
    @Test fun aLineOnASilentSideWhileTheOtherTalksGoes() {
        assertTrue(QuietSide.isPhantom(line(Speaker.THEM), left = levels(-12.0), right = levels(-60.0)))
    }

    /** The test call's case: their side loud - whatever was heard, it was speech. */
    @Test fun aLoudSideIsKept() {
        assertFalse(QuietSide.isPhantom(line(Speaker.THEM), left = levels(-10.0), right = levels(-9.4)))
    }

    /** A quiet speaker in a quiet call is a quiet speaker, not a phantom. */
    @Test fun bothQuietIsKept() {
        assertFalse(QuietSide.isPhantom(line(Speaker.YOU), left = levels(-48.0), right = levels(-52.0)))
    }

    @Test fun aMixedLineIsNeverDropped() {
        assertFalse(QuietSide.isPhantom(line(Speaker.NONE), left = levels(-12.0), right = levels(-90.0)))
    }
}
