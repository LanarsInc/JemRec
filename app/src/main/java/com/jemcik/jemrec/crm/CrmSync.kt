package com.jemcik.jemrec.crm

import android.content.Context
import com.jemcik.jemrec.BuildConfig
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.crm.api.SalesEngineClient
import com.jemcik.jemrec.crm.db.CrmDatabase
import com.jemcik.jemrec.crm.db.WorkCallDao
import com.jemcik.jemrec.crm.db.WorkCallEntry

/**
 * The Sales Engine's queue, worked through in order (research R6). Every call
 * has one next thing to do, decided by WorkCallMachine from its stored state;
 * this does it, stores what the server said, and moves on.
 *
 * @param now the clock, a parameter so tests can move it
 */
class CrmSync(
    private val calls: WorkCallDao,
    private val store: SessionStore,
    private val client: SalesEngineClient,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Does everything due now, one request at a time.
     *
     * @return when the next thing falls due, or null when nothing waits
     */
    suspend fun runDue(): Long? {
        var next: Long? = null
        for (call in calls.all()) {
            val due = step(call) ?: continue
            next = if (next == null) due else minOf(next, due)
        }
        return next
    }

    /** One call's next step, if it is due; returns when it next needs attention. */
    private suspend fun step(call: WorkCallEntry): Long? = null

    companion object {
        fun of(context: Context): CrmSync = CrmSync(CrmDatabase.of(context).calls(), store(context), client(context))

        fun store(context: Context) = SessionStore(Prefs.of(context), KeystoreTokenSealer)

        fun client(context: Context): SalesEngineClient {
            val store = store(context)
            return SalesEngineClient(BuildConfig.SALES_ENGINE_URL, BuildConfig.VERSION_NAME, store::token)
        }
    }
}
