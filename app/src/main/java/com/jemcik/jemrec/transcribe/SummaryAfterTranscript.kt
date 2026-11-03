package com.jemcik.jemrec.transcribe

import android.content.Context
import android.util.Log
import com.jemcik.jemrec.summary.SummaryWorker
import com.jemcik.jemrec.transcribe.model.ModelRepository

/**
 * "Summarise after each transcript": a summary queued behind the transcript
 * that just finished, when a summary model this phone can hold is on it. No
 * model, no summary - nothing is downloaded without the user's tap.
 */
object SummaryAfterTranscript {
    fun queue(context: Context, recording: String, contact: String?) {
        val model = SummaryWorker.chosenModel(context) ?: return
        if (!ModelRepository.isInstalled(context, model)) {
            Log.i("JR:Summary", "No summary after the transcript: ${model.id} is not downloaded")
            return
        }
        SummaryWorker.enqueue(context, recording, contact)
    }
}
