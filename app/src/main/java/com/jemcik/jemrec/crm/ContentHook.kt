package com.jemcik.jemrec.crm

import android.content.Context
import android.util.Log
import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.api.Bodies
import com.jemcik.jemrec.crm.db.CrmDatabase
import com.jemcik.jemrec.crm.db.WorkCallEntry
import com.jemcik.jemrec.summary.CallSummary
import com.jemcik.jemrec.summary.SummaryWorker
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.model.ModelRepository
import com.jemcik.jemrec.transcripts.TranscriptRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Step 2's moments (§8): a recording's summary is done, or its transcript
 * cannot reach the CRM. Called from the transcription and summary workers
 * and the trash; each finds the recording's work call, if it has one, and
 * leaves the sending to the queue.
 *
 * The CRM takes a transcript only with its summary, so a transcript without
 * one - summaries off, no summary model, a summary that failed - is "failed"
 * there (FR-031). Only the first finished texts go (FR-035).
 */
object ContentHook {

    /** A transcript finished. If no summary will follow it, the CRM gets "failed". */
    fun transcribed(context: Context, recording: String, lines: Int) = later {
        val summaryComes = lines > 0 && TranscribeSettings.of(context).summariseAfterTranscript &&
            SummaryWorker.chosenModel(context)?.let { ModelRepository.isInstalled(context, it) } == true
        if (!summaryComes) markFailed(context, recording, if (lines == 0) "no speech" else "no summary")
    }

    fun transcriptionFailed(context: Context, recording: String) = later { markFailed(context, recording, "transcription failed") }

    fun summaryFailed(context: Context, recording: String) = later { markFailed(context, recording, "summary failed") }

    /** A summary was stored: the texts are built now, from what the phone keeps. */
    fun summarised(context: Context, recording: String) = later {
        val calls = CrmDatabase.of(context).calls()
        val call = calls.byRecording(recording) ?: return@later
        val repo = TranscriptRepository.of(context)
        val transcript = repo.load(recording) ?: return@later markFailed(context, recording, "no transcript")
        val summary = repo.summary(recording)?.let { CallSummary.parse(it.json) } ?: return@later markFailed(context, recording, "no summary")
        val content = ContentText.content(transcript.lines, summary, transcript.facts.language)
            ?: return@later markFailed(context, recording, "empty transcript")
        ready(call, Bodies.ready(content))?.let {
            calls.update(it)
            CrmSyncWorker.now(context)
        }
    }

    /** The recording went to the trash before its texts reached the CRM (FR-036). */
    fun recordingDeleted(context: Context, recording: String) = later { markFailed(context, recording, "recording deleted") }

    private suspend fun markFailed(context: Context, recording: String, why: String) {
        val calls = CrmDatabase.of(context).calls()
        val call = calls.byRecording(recording) ?: return
        failed(call)?.let {
            Log.i("JemRec", "crm: no content for a work call: $why")
            calls.update(it)
            CrmSyncWorker.now(context)
        }
    }

    /** Pure: the call with its texts to send, or null - only the first texts go, and failed may be followed once. */
    fun ready(c: WorkCallEntry, body: String): WorkCallEntry? = when (Content.of(c.contentState)) {
        Content.EXPECTED, Content.NOT_EXPECTED, Content.FAILED_TO_SEND, Content.SENT_FAILED ->
            c.copy(contentState = Content.READY_TO_SEND.name, contentBody = body, contentDueAtMs = 0)
        else -> null
    }

    /** Pure: the call with "failed" to send, or null when texts are on their way or went already. */
    fun failed(c: WorkCallEntry): WorkCallEntry? = when (Content.of(c.contentState)) {
        Content.EXPECTED -> c.copy(contentState = Content.FAILED_TO_SEND.name, contentDueAtMs = 0)
        else -> null
    }

    private fun later(block: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { block() }.onFailure { Log.w("JemRec", "crm: content hook failed", it) }
        }
    }
}
