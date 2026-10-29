package com.jemcik.jemrec.summary

import com.jemcik.jemrec.transcribe.model.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both catalogues, for what is silently fatal if wrong (a digest or an id),
 * and which summary models a phone is offered.
 */
class SummaryModelTest {

    /** The Galaxy A24's total memory, as /proc/meminfo reported it (2026-10-29). */
    private val a24 = 5_795_700L * 1024

    @Test fun a6GbPhoneIsOfferedQwenNotGemma4() {
        assertTrue(SummaryModel.QWEN3_1_7B_Q4_K_M.fits(a24))
        assertFalse(SummaryModel.GEMMA_4_E2B_QAT_Q4_K_XL.fits(a24))
        assertEquals(SummaryModel.QWEN3_1_7B_Q4_K_M, SummaryModel.bestFor(a24))
    }

    @Test fun an8GbPhoneGetsGemma4() {
        // An "8 GB" phone reports about 7.5 GB to Android.
        assertEquals(SummaryModel.GEMMA_4_E2B_QAT_Q4_K_XL, SummaryModel.bestFor(7_500_000_000L))
    }

    @Test fun aPhoneTooSmallForAnyIsOfferedNone() {
        assertNull(SummaryModel.bestFor(2_000_000_000L))
    }

    @Test fun gemma4SaysItNeedsAn8GbPhone() {
        assertEquals(8, SummaryModel.GEMMA_4_E2B_QAT_Q4_K_XL.needsGb)
    }

    @Test fun digestsAreLowerCaseSha256() {
        val digests = SummaryModel.entries.map { it.sha256 } + SpeechModel.entries.map { it.sha256 }
        digests.forEach { d ->
            assertEquals(d, 64, d.length)
            assertTrue(d, d.all { it in "0123456789abcdef" })
        }
    }

    /** Ids are stored with each transcript; file names share one directory. */
    @Test fun idsAndFileNamesAreUnique() {
        val ids = SummaryModel.entries.map { it.id } + SpeechModel.entries.map { it.id }
        val files = SummaryModel.entries.map { it.fileName } + SpeechModel.entries.map { it.fileName }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(files.size, files.toSet().size)
    }

    @Test fun norwegianModelsAreForNorwegianOnly() {
        assertTrue(SpeechModel.NB_SMALL_Q5_0.isFor("no"))
        assertFalse(SpeechModel.NB_SMALL_Q5_0.isFor("en"))
        assertFalse(SpeechModel.NB_SMALL_Q5_0.isFor(null))
        assertTrue(SpeechModel.TURBO_Q8_0.isFor("no"))
        assertTrue(SpeechModel.TURBO_Q8_0.isFor(null))
    }

    @Test fun twoFastCoresGetSmallFourGetTurbo() {
        assertEquals(SpeechModel.SMALL_Q5_1, SpeechModel.recommendedFor(2))
        assertEquals(SpeechModel.TURBO_Q8_0, SpeechModel.recommendedFor(4))
    }
}
