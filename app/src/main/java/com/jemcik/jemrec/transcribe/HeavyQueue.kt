package com.jemcik.jemrec.transcribe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.summary.SummaryEngine
import java.util.UUID
import java.util.concurrent.Executors

/**
 * What keeps the one chain of transcriptions and summaries (TranscriptionWorker.CHAIN)
 * whole: no work in it ends failed or cancelled.
 *
 * WorkManager's chain breaks on either. A failed work fails everything queued
 * behind it; a cancelled one cancels everything behind it; and the next work
 * queued with APPEND_OR_REPLACE onto a chain holding either starts the chain
 * anew, cancelling the one running. Measured on the Galaxy A24, 2026-10-30:
 * the user cancelled a call's transcription waiting in the queue, tapped
 * Transcript again, and the transcription running for another call stopped.
 *
 * So a work that did not finish its job still succeeds, saying why in its
 * output ([finished]), and a Cancel does not cancel the work: it marks it
 * ([stop]), and the work, waiting or running, ends itself as stopped. Android
 * stopping a work (its time is up, a constraint lost) is a retry, which keeps
 * the chain as it is.
 */
object HeavyQueue {

    private const val SKIP = "jr_queue_stopped"
    private const val KEY_FINISHED_AT = "out_finished_at"
    private val background = Executors.newSingleThreadExecutor()

    /**
     * Ends a work's run: success, with [output] and when it ended. A screen
     * takes the newest finished one as the state of its recording ([newest]).
     */
    fun finished(output: Data = Data.EMPTY): androidx.work.ListenableWorker.Result =
        androidx.work.ListenableWorker.Result.success(
            Data.Builder().putAll(output).putLong(KEY_FINISHED_AT, System.currentTimeMillis()).build(),
        )

    fun finished(vararg pairs: Pair<String, Any?>) = finished(workDataOf(*pairs))

    /** Of [infos], the one that ended last; null when none has. */
    fun newest(infos: List<WorkInfo>): WorkInfo? =
        infos.filter { it.state.isFinished }.maxByOrNull { it.outputData.getLong(KEY_FINISHED_AT, 0L) }

    /**
     * Stops every work tagged [tag] that has not ended, waiting or running:
     * each is marked, and the engines asked to stop, so a running one ends at
     * once and a waiting one as soon as it starts.
     */
    fun stop(context: Context, tag: String, abort: () -> Unit) {
        val app = context.applicationContext
        val infos = WorkManager.getInstance(app).getWorkInfosByTag(tag)
        infos.addListener({
            val pending = runCatching { infos.get() }.getOrDefault(emptyList()).filterNot { it.state.isFinished }
            if (pending.isEmpty()) return@addListener
            mark(app, pending.map { it.id })
            if (pending.any { it.state == WorkInfo.State.RUNNING }) abort()
        }, background)
    }

    /** Whether the work [id] was asked to stop. */
    fun isStopped(context: Context, id: UUID): Boolean =
        id.toString() in Prefs.of(context).getStringSet(SKIP, emptySet()).orEmpty()

    /** Forgets [id]'s mark, once the work has ended on it. */
    fun forget(context: Context, id: UUID) {
        val prefs = Prefs.of(context)
        val now = prefs.getStringSet(SKIP, emptySet()).orEmpty()
        if (id.toString() in now) prefs.edit { putStringSet(SKIP, now - id.toString()) }
    }

    private fun mark(context: Context, ids: List<UUID>) {
        val prefs = Prefs.of(context)
        synchronized(this) {
            prefs.edit { putStringSet(SKIP, prefs.getStringSet(SKIP, emptySet()).orEmpty() + ids.map { it.toString() }) }
        }
    }

    /** The notification's Cancel: the same as the screen's, never WorkManager's own cancel. */
    fun cancelIntent(context: Context, tag: String, summary: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, tag.hashCode(),
            Intent(context, CancelReceiver::class.java).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_SUMMARY, summary),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val EXTRA_TAG = "tag"
    private const val EXTRA_SUMMARY = "summary"

    /** Receives the notification's Cancel. Not exported: only this app's notification sends it. */
    class CancelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tag = intent.getStringExtra(EXTRA_TAG) ?: return
            if (intent.getBooleanExtra(EXTRA_SUMMARY, false)) stop(context, tag) { SummaryEngine.requestAbort() }
            else stop(context, tag) { TranscriptionEngine.requestAbort() }
        }
    }
}
