package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished transcription's output is merged into the next one's input, over
 * it, because they share one chain. An output key that is also an input key
 * renames the next recording - which happened on the A24 (2026-10-29).
 */
class TranscriptionWorkerKeysTest {
    @Test fun outputNeverOverwritesInput() {
        assertTrue(TranscriptionWorker.INPUT_KEYS.intersect(TranscriptionWorker.OUTPUT_KEYS).isEmpty())
    }
}
