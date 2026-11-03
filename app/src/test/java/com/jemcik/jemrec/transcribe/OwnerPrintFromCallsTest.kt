package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerPrintFromCallsTest {

    private val you = floatArrayOf(1f, 0f, 0f)
    private val youAgain = floatArrayOf(0.95f, 0.1f, 0.1f)
    private val them = floatArrayOf(0f, 1f, 0f)
    private val radio = floatArrayOf(0f, 0f, 1f)

    private val good = OwnerPrintFromCalls.Call(
        leftSpeechMs = 45_000, speakerphone = false, sideCorrelation = 0.0,
        leftWindows = listOf(you, youAgain), right = them,
    )

    @Test fun aPlainCallOfYoursOffersYourVoice() {
        val offered = OwnerPrintFromCalls.offer(good)!!
        assert(VoiceLink.cosine(offered, you) > 0.95)
    }

    @Test fun tooLittleSpeechOffersNothing() = assertNull(OwnerPrintFromCalls.offer(good.copy(leftSpeechMs = 12_000)))

    @Test fun speakerphoneOffersNothing() = assertNull(OwnerPrintFromCalls.offer(good.copy(speakerphone = true)))

    /** Route unknown: an echo of the other side moves both channels together. */
    @Test fun anUnknownRouteNeedsSidesThatMoveApart() {
        assertNull(OwnerPrintFromCalls.offer(good.copy(speakerphone = null, sideCorrelation = 0.5)))
        assertNotNull(OwnerPrintFromCalls.offer(good.copy(speakerphone = null, sideCorrelation = -0.4)))
    }

    /** R16's test calls: the left side with audio played into the phone - not one voice. */
    @Test fun aLeftSideThatIsNotOneVoiceOffersNothing() =
        assertNull(OwnerPrintFromCalls.offer(good.copy(leftWindows = listOf(you, radio))))

    /** R16: one test call's left side was 0.85 to the other side's - the other person, heard back. */
    @Test fun aLeftSideLikeTheRightOffersNothing() =
        assertNull(OwnerPrintFromCalls.offer(good.copy(leftWindows = listOf(them, them), right = them)))

    @Test fun oneWindowIsTooLittleToTellIfItIsOneVoice() =
        assertNull(OwnerPrintFromCalls.offer(good.copy(leftWindows = listOf(you))))

    @Test fun theNewestFiveCallsAreKept() {
        var kept = emptyList<FloatArray>()
        repeat(4) { kept = OwnerPrintFromCalls.keep(kept, them) }
        repeat(5) { kept = OwnerPrintFromCalls.keep(kept, you) }
        assertEquals(5, kept.size)
        assert(VoiceLink.cosine(OwnerPrintFromCalls.print(kept)!!, you) > 0.999)
    }

    @Test fun noCallsNoPrint() = assertNull(OwnerPrintFromCalls.print(emptyList()))
}
