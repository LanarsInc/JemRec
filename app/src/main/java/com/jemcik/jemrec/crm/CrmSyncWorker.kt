package com.jemcik.jemrec.crm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs the Sales Engine's queue: whatever is due, one request at a time, then
 * books itself for the next due moment (research R6).
 *
 * Not part of the transcription chain. That chain must never fail or be
 * cancelled, because a failed work takes the ones queued behind it
 * (ARCHITECTURE, "When"); this one may simply run again, and it waits for the
 * network, which the heavy work never does.
 */
class CrmSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val next = CrmSync.of(applicationContext).runDue()
        if (next != null) later(applicationContext, next)
        return Result.success()
    }

    companion object {
        private const val NOW = "crm-sync"
        private const val LATER = "crm-sync-later"

        private val ONLINE = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Run as soon as there is a network. KEEP: a run already queued or
         * running takes whatever is due when it reads the queue - and a run
         * that misses a call made during it books the next one itself.
         */
        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CrmSyncWorker>().setConstraints(ONLINE).build(),
            )
        }

        /** Run at [atMs] (or when the network is back after it): REPLACE, so only the earliest wait is kept. */
        fun later(context: Context, atMs: Long) {
            val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
            WorkManager.getInstance(context).enqueueUniqueWork(
                LATER, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CrmSyncWorker>()
                    .setConstraints(ONLINE)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }
}
