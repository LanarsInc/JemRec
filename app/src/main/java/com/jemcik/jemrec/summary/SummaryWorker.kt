package com.jemcik.jemrec.summary

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jemcik.jemrec.transcribe.HeavyWorkNotification
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.TranscriptionWorker
import com.jemcik.jemrec.transcribe.model.ModelRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Summarises one recording's transcript in the background, in a foreground
 * service, cancellable - as TranscriptionWorker transcribes.
 *
 * It joins the transcriptions' own chain (TranscriptionWorker.CHAIN), so a
 * summary and a transcription never run at once: on a 6 GB phone the two
 * models together would not fit, and the engines' shared lock would only make
 * one wait inside a foreground service doing nothing.
 */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val recording = inputData.getString(KEY_RECORDING) ?: return@coroutineScope Result.failure()
        val contact = inputData.getString(KEY_CONTACT)
        val app = applicationContext

        val model = chosenModel(app) ?: return@coroutineScope Result.failure(workDataOf(KEY_ERROR to ERROR_NO_MODEL))
        val path = ModelRepository.pathFor(app, model)
            ?: return@coroutineScope Result.failure(workDataOf(KEY_ERROR to ERROR_NO_MODEL))

        val title = "Summarising ${contact ?: "a call"}"
        val cancel = WorkManager.getInstance(app).createCancelPendingIntent(id)
        runCatching { setForeground(HeavyWorkNotification.forWork(app, title, null, cancel)) }
            .onFailure { Log.w(TAG, "Could not run in the foreground: ${it.message}") }

        // A chunk at a time is all the progress there is: llama reports none of its own.
        var done = 0
        var total = 0
        val ticker = launch {
            try {
                while (isActive) {
                    delay(1000)
                    if (isStopped) SummaryEngine.requestAbort()
                }
            } finally {
                // Cancelled with the worker before the check above sees it: as TranscriptionWorker's.
                if (isStopped) SummaryEngine.requestAbort()
            }
        }
        val outcome = try {
            SummaryRunner.of(app).run(recording, contact, model, path.absolutePath, shouldStop = { isStopped }) { completed, chunks ->
                done = completed
                total = chunks
                val percent = SummaryProgress.chunkAnchor(completed, chunks)
                launch {
                    setProgress(workDataOf(KEY_PERCENT to percent))
                    runCatching { setForeground(HeavyWorkNotification.forWork(app, title, percent, cancel)) }
                }
            }
        } finally {
            ticker.cancel()
        }
        Log.i(TAG, "Summary ended: ${outcome.javaClass.simpleName} after $done of $total chunk(s)")

        // Output keys never repeat an input key: the chain merges outputs into the next work's input.
        when (outcome) {
            is SummaryOutcome.Done -> Result.success()
            SummaryOutcome.Stopped -> Result.failure(workDataOf(KEY_ERROR to ERROR_STOPPED))
            SummaryOutcome.NoTranscript -> Result.failure(workDataOf(KEY_ERROR to ERROR_NO_TRANSCRIPT))
            is SummaryOutcome.NoRoom -> Result.failure(workDataOf(KEY_ERROR to ERROR_NO_ROOM))
            is SummaryOutcome.Failed -> Result.failure(workDataOf(KEY_ERROR to outcome.reason))
        }
    }

    companion object {
        private const val TAG = "JR:Summary"

        fun tagFor(recording: String) = "jr_sum:$recording"

        const val KEY_RECORDING = "recording"
        const val KEY_CONTACT = "contact"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "out_sum_error"
        const val ERROR_NO_MODEL = "no-model"
        const val ERROR_STOPPED = "stopped"
        const val ERROR_NO_TRANSCRIPT = "no-transcript"
        const val ERROR_NO_ROOM = "no-room"

        internal val INPUT_KEYS = setOf(KEY_RECORDING, KEY_CONTACT)
        internal val OUTPUT_KEYS = setOf(KEY_ERROR)

        /**
         * The summary model to use: the one chosen in Settings when this phone
         * can hold it, else the best this phone can hold (SummaryModel.fits).
         */
        fun chosenModel(context: Context): SummaryModel? {
            val total = totalMemory(context)
            SummaryModel.fromId(TranscribeSettings.of(context).summaryModelId)?.takeIf { it.fits(total) }?.let { return it }
            return SummaryModel.bestFor(total)
        }

        fun totalMemory(context: Context): Long {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return info.totalMem
        }

        /** Queues a summary of [recording] behind whatever transcription or summary is running. */
        fun enqueue(context: Context, recording: String, contact: String?) {
            val request = OneTimeWorkRequestBuilder<SummaryWorker>()
                .setInputData(workDataOf(KEY_RECORDING to recording, KEY_CONTACT to contact))
                .addTag(tagFor(recording))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TranscriptionWorker.CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context, recording: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(recording))
            SummaryEngine.requestAbort()
        }
    }
}
