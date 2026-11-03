package com.jemcik.jemrec.ui

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import com.jemcik.jemrec.summary.SummaryWorker
import com.jemcik.jemrec.transcribe.HeavyQueue
import com.jemcik.jemrec.transcribe.TranscriptionWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * What a recording's screen makes of its works, now that none fails
 * (HeavyQueue): an error is in a succeeded work's output, and the newest
 * ended one decides.
 */
class WorkStateTest {

    private fun ended(vararg pairs: Pair<String, Any?>): WorkInfo {
        val out = (HeavyQueue.finished(*pairs) as ListenableWorker.Result.Success).outputData
        Thread.sleep(2) // each a different moment, as two runs are
        return WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet(), out)
    }

    private fun waiting(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY)

    @Test fun aRunThatEndedWellLeavesTheTranscript() {
        assertEquals(JobState.Idle, TranscriptsController.jobOf(listOf(ended(TranscriptionWorker.KEY_LINES to 3))))
    }

    @Test fun anErrorIsShownFromASucceededWork() {
        assertEquals(
            JobState.Failed("The speech model is not on the phone."),
            TranscriptsController.jobOf(listOf(ended(TranscriptionWorker.KEY_ERROR to TranscriptionWorker.ERROR_NO_MODEL))),
        )
    }

    @Test fun theNewestEndedRunDecides() {
        val failed = ended(TranscriptionWorker.KEY_ERROR to "broke")
        val then = ended(TranscriptionWorker.KEY_LINES to 3)
        assertEquals(JobState.Idle, TranscriptsController.jobOf(listOf(then, failed)))
        val summarised = ended()
        val noRoom = ended(SummaryWorker.KEY_ERROR to SummaryWorker.ERROR_NO_ROOM)
        assertEquals(
            JobState.Failed("Not enough free memory right now. Close other apps and try again."),
            TranscriptsController.summaryJobOf(listOf(noRoom, summarised)),
        )
    }

    @Test fun aStoppedRunIsNoError() {
        assertEquals(JobState.Idle, TranscriptsController.jobOf(listOf(ended(TranscriptionWorker.KEY_ERROR to TranscriptionWorker.ERROR_STOPPED))))
    }

    @Test fun waitingIsQueued() {
        assertEquals(JobState.Queued, TranscriptsController.jobOf(listOf(ended(), waiting(WorkInfo.State.BLOCKED))))
    }
}
