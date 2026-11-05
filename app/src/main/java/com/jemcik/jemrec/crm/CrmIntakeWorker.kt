package com.jemcik.jemrec.crm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay

/**
 * Takes the calls the call log shows since the last look (CallIntake), and
 * hands them to the queue.
 *
 * No network needed, unlike the queue: the after-call notification with
 * Private must come up in a dead zone too. After a call ends Android writes
 * its row a moment later, so then this looks again for up to half a minute.
 */
class CrmIntakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val afterCall = inputData.getBoolean(KEY_AFTER_CALL, false)
        var taken = CallIntake.sweep(applicationContext)
        var waited = 0L
        while (afterCall && taken == 0 && waited < ROW_WAIT_MS) {
            delay(ROW_POLL_MS)
            waited += ROW_POLL_MS
            taken = CallIntake.sweep(applicationContext)
        }
        CrmSyncWorker.now(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "crm-intake"
        private const val KEY_AFTER_CALL = "after_call"
        private const val ROW_WAIT_MS = 30_000L
        private const val ROW_POLL_MS = 3_000L

        /** A phone call just ended: its row is on its way. */
        fun afterCall(context: Context) = enqueue(context, afterCall = true)

        /** The app opened, or a periodic check ran. */
        fun now(context: Context) = enqueue(context, afterCall = false)

        private fun enqueue(context: Context, afterCall: Boolean) {
            if (!active(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                // After a call, the newest request wins: its wait for the row starts now.
                if (afterCall) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CrmIntakeWorker>().setInputData(workDataOf(KEY_AFTER_CALL to afterCall)).build(),
            )
        }

        /** Not a seller of LANARS's, or signed out on purpose: nothing to take. */
        private fun active(context: Context): Boolean {
            val store = CrmSync.store(context)
            return store.setupAtMs != null && store.state != SessionStore.State.SIGNED_OUT && store.state != SessionStore.State.BLOCKED
        }
    }
}
