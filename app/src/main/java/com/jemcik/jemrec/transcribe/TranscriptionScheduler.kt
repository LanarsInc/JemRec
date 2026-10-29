package com.jemcik.jemrec.transcribe

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.transcripts.TranscriptRepository
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Transcribing without being asked one call at a time (TranscribeSettings.mode).
 *
 * - **After each call**: queued as the recording is saved, from the call's
 *   own service. Expedited, so it runs at once and at a foreground's
 *   priority: started from the background without that, Android refuses the
 *   foreground service and the A24 ran 13 times slower (research.md R14).
 *   Whether an expedited start from the call's service gets the foreground
 *   service is to be measured with a real call (tasks T086).
 * - **Overnight while charging**: a daily job that queues every recording of
 *   the last [OVERNIGHT_DAYS] days without a transcript, newest first. Hours
 *   of full CPU, so only on the charger with the battery not low (CallVault's
 *   rule).
 * - **Only when I ask**: nothing here.
 */
object TranscriptionScheduler {

    private const val TAG = "JR:Transcribe"
    const val OVERNIGHT = "jr_tx_overnight"

    /** Recordings older than this are left for the user to ask for: the backlog of a first install can be months. */
    const val OVERNIGHT_DAYS = 30

    /** When the overnight job may start, at the earliest: half past one. */
    private const val OVERNIGHT_HOUR = 1
    private const val OVERNIGHT_MINUTE = 30

    /** Makes the scheduled work match the setting: at app start, and whenever the setting changes. */
    fun apply(context: Context) {
        val settings = TranscribeSettings.of(context)
        val work = WorkManager.getInstance(context)
        if (settings.mode != TranscribeMode.OVERNIGHT) {
            work.cancelUniqueWork(OVERNIGHT)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresCharging(settings.chargingOnly)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<OvernightWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(delayUntil(OVERNIGHT_HOUR, OVERNIGHT_MINUTE, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniquePeriodicWork(OVERNIGHT, ExistingPeriodicWorkPolicy.UPDATE, request)
        Log.i(TAG, "Overnight transcription scheduled (charging only: ${settings.chargingOnly})")
    }

    /** A recording just saved: transcribe it now if the user asked for that. */
    fun afterCall(context: Context, recording: String, uri: Uri, contact: String?) {
        if (TranscribeSettings.of(context).mode != TranscribeMode.AFTER_CALL) return
        TranscriptionWorker.enqueue(context, recording, uri, contact, automatic = true, expedited = true)
        Log.i(TAG, "Queued a transcription after the call")
    }

    /** Milliseconds from [now] to the next [hour]:[minute], local time; at least a minute. */
    internal fun delayUntil(hour: Int, minute: Int, now: Long): Long {
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (next.timeInMillis - now).coerceAtLeast(60_000L)
    }

    /**
     * Which recordings the overnight run takes: not transcribed, from the
     * last [OVERNIGHT_DAYS] days, newest first. Plain, for the tests.
     */
    internal fun backlog(recordings: List<Pair<String, Long>>, transcribed: Set<String>, now: Long): List<String> =
        recordings
            .filter { (name, startedAt) -> name !in transcribed && now - startedAt <= OVERNIGHT_DAYS * 86_400_000L }
            .sortedByDescending { it.second }
            .map { it.first }

    /** The daily job: it only queues; each transcription is its own work in the chain. */
    class OvernightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext
            val all = Recordings.list(app)
            val transcribed = TranscriptRepository.of(app).observeTranscribed().first()
            val now = System.currentTimeMillis()
            val names = backlog(all.mapNotNull { r -> r.startedAtMillis?.let { r.name to it } }, transcribed, now).toSet()
            all.filter { it.name in names }.sortedByDescending { it.startedAtMillis }.forEach { r ->
                TranscriptionWorker.enqueue(app, r.name, r.uri, r.caller, automatic = true)
            }
            Log.i(TAG, "Overnight: queued ${names.size} recording(s)")
            return Result.success()
        }
    }
}
