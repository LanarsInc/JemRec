package com.jemcik.jemrec.transcribe

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jemcik.jemrec.transcripts.TranscriptRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Transcribes one recording in the background, in a foreground service so
 * Android keeps it (HeavyWorkNotification), reporting progress and stopping
 * when cancelled.
 *
 * All transcriptions go through one unique chain ([CHAIN]), appended to, so
 * they run one after another: two at once would each take twice as long on
 * the same cores, and could not both hold a model on a 6 GB phone.
 *
 * Cancelling the work stops whisper mid-run (TranscriptionEngine.requestAbort):
 * its one blocking native call ignores coroutine cancellation, so without
 * that the phone would keep working until the end of the call.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val recording = inputData.getString(KEY_RECORDING) ?: return@coroutineScope HeavyQueue.finished()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return@coroutineScope HeavyQueue.finished()
        val contact = inputData.getString(KEY_CONTACT)
        val automatic = inputData.getBoolean(KEY_AUTOMATIC, false)
        val app = applicationContext
        // Cancelled while it waited: it ends here, as stopped, and the chain goes on (HeavyQueue).
        if (HeavyQueue.isStopped(app, id)) {
            HeavyQueue.forget(app, id)
            return@coroutineScope HeavyQueue.finished(KEY_ERROR to ERROR_STOPPED)
        }
        val settings = TranscribeSettings.of(app)
        val repository = TranscriptRepository.of(app)

        val language = ModelChoice.language(repository.languageFor(recording), settings.language)
        val (_, ready) = ModelChoice.speechModelFor(app, language)
        if (ready == null) {
            Log.w(TAG, "No speech model on the phone; nothing transcribed")
            return@coroutineScope HeavyQueue.finished(KEY_ERROR to ERROR_NO_MODEL)
        }

        val title = "Transcribing ${contact ?: "a call"}"
        val cancel = HeavyQueue.cancelIntent(app, tagFor(recording), summary = false)
        runCatching { setForeground(HeavyWorkNotification.forWork(app, title, null, cancel)) }
            .onFailure { Log.w(TAG, "Could not run in the foreground: ${it.message}") }

        // Progress, once a second: polled from whisper, never pushed from its thread.
        val ticker = launch {
            var last = -1
            try {
                while (isActive) {
                    delay(1000)
                    if (isStopped || HeavyQueue.isStopped(app, id)) TranscriptionEngine.requestAbort()
                    val percent = TranscriptionEngine.progressPercent()
                    if (percent != last && TranscriptionEngine.isRunning) {
                        last = percent
                        setProgress(workDataOf(KEY_RECORDING to recording, KEY_PERCENT to percent))
                        runCatching { setForeground(HeavyWorkNotification.forWork(app, title, percent, cancel)) }
                    }
                }
            } finally {
                // A stopped worker's coroutine is cancelled, and this ticker with it, before
                // the check above sees isStopped: whisper, which ignores cancellation, ran on.
                // Measured on the Galaxy A24, 2026-10-30: a cancelled run went on for 97 s
                // while the next transcription waited for the engine.
                if (isStopped) TranscriptionEngine.requestAbort()
            }
        }
        val outcome = try {
            TranscriptionRunner.of(app).run(
                RunRequest(
                    recording = recording,
                    uri = uri,
                    modelId = ready.id,
                    modelPath = ready.file.absolutePath,
                    language = language,
                    contact = contact,
                    sides = settings.sides,
                    threads = TranscriptionRunner.threadsFor(app),
                ),
                shouldStop = { isStopped || HeavyQueue.isStopped(app, id) },
            )
        } finally {
            ticker.cancel()
        }

        // The output never carries an input key. Every transcription joins one
        // chain, and WorkManager merges a finished work's output INTO the next
        // one's input, over its own (OverwritingInputMerger): an output with
        // the recording's name renamed the next recording. Measured on the A24,
        // 2026-10-29: a Telegram call's transcript was saved under the phone
        // call transcribed before it.
        val cancelled = HeavyQueue.isStopped(app, id).also { if (it) HeavyQueue.forget(app, id) }
        when (outcome) {
            is RunOutcome.Done -> {
                if (automatic) TranscriptNotices.ready(app, recording, contact, outcome.lines)
                // A summary follows when asked for and a model is here; it joins the same chain.
                if (settings.summariseAfterTranscript && outcome.lines > 0) SummaryAfterTranscript.queue(app, recording, contact)
                HeavyQueue.finished(KEY_LINES to outcome.lines)
            }
            // Stopped by the user ends it; stopped by Android (time's up for an
            // expedited job, a constraint lost) is tried again later. Neither
            // fails it, which would break the chain (HeavyQueue).
            RunOutcome.Stopped -> when {
                cancelled -> HeavyQueue.finished(KEY_ERROR to ERROR_STOPPED)
                isStopped && stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP -> Result.retry()
                else -> HeavyQueue.finished(KEY_ERROR to ERROR_STOPPED)
            }
            is RunOutcome.Failed -> {
                if (automatic) TranscriptNotices.failed(app, recording, contact, outcome.reason)
                HeavyQueue.finished(KEY_ERROR to outcome.reason)
            }
        }
    }

    /** For an expedited start on a phone that needs one shown first; the same notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        HeavyWorkNotification.forWork(applicationContext, "Transcribing a call", null, null)

    companion object {
        private const val TAG = "JR:Transcribe"

        /** The one chain every transcription joins. */
        const val CHAIN = "jr_tx_now"

        /** Each work's tag: which recording it is for, so a screen can find its own. */
        fun tagFor(recording: String) = "jr_tx:$recording"

        const val KEY_RECORDING = "recording"
        const val KEY_URI = "uri"
        const val KEY_CONTACT = "contact"
        /** Started by a setting rather than a tap: its end is announced, as nobody is watching it. */
        const val KEY_AUTOMATIC = "automatic"
        const val KEY_PERCENT = "percent"
        // Output keys: never the same as an input key (see doWork's end).
        const val KEY_LINES = "out_lines"
        const val KEY_ERROR = "out_error"
        const val ERROR_NO_MODEL = "no-model"

        /** What goes in, and what comes out: kept apart, which a test holds to. */
        internal val INPUT_KEYS = setOf(KEY_RECORDING, KEY_URI, KEY_CONTACT, KEY_AUTOMATIC)
        internal val OUTPUT_KEYS = setOf(KEY_LINES, KEY_ERROR, "out_finished_at")
        const val ERROR_STOPPED = "stopped"

        /** Queues [recording] behind any transcription already running. */
        fun enqueue(context: Context, recording: String, uri: Uri, contact: String?, automatic: Boolean = false, expedited: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(workDataOf(KEY_RECORDING to recording, KEY_URI to uri.toString(), KEY_CONTACT to contact, KEY_AUTOMATIC to automatic))
                .addTag(tagFor(recording))
                .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Stops [recording]'s transcription, running or waiting - without
         * WorkManager's cancel, which would break the chain (HeavyQueue).
         */
        fun cancel(context: Context, recording: String) {
            HeavyQueue.stop(context, tagFor(recording)) {
                if (TranscriptionInFlight.isAmong(listOf(recording))) TranscriptionEngine.requestAbort()
            }
        }

        fun percentOf(progress: Data): Int? = progress.getInt(KEY_PERCENT, -1).takeIf { it >= 0 }
    }
}
