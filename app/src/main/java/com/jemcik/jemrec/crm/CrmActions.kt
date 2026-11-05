package com.jemcik.jemrec.crm

import android.content.Context
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.CallStatus
import com.jemcik.jemrec.crm.api.DealChoice
import com.jemcik.jemrec.crm.api.NextCall
import com.jemcik.jemrec.crm.api.Outcome
import com.jemcik.jemrec.crm.db.CrmDatabase
import com.jemcik.jemrec.crm.db.WorkCallDao
import com.jemcik.jemrec.crm.db.WorkCallEntry
import org.json.JSONObject

/**
 * What the seller does to a work call - a deal or Other, an outcome, a next
 * call - from the notification or the call sheet. Each is stored on the call
 * and left to the queue to send (CrmSync). There is no Private: the API of
 * 2026-11-03 dropped it, every call on the work line is sent.
 */
object CrmActions {

    /** The seller's choice for a call, from the sheet. */
    data class Choice(val choice: DealChoice, val dealId: Int?, val outcome: Outcome, val next: NextCall?)

    /** The seller's choice, sent at once: before step 1 it is step 1; after, a pick (§7). */
    suspend fun choose(context: Context, id: Long, choice: Choice, nowMs: Long = System.currentTimeMillis()) {
        val calls: WorkCallDao = CrmDatabase.of(context).calls()
        val c = calls.byId(id) ?: return
        val updated = chosen(c, choice, nowMs) ?: return
        calls.update(updated)
        CrmNotifications.show(context, updated)
        CrmSyncWorker.now(context)
    }

    /** Pure: the call with the seller's choice in it, or null when it can take none. */
    fun chosen(c: WorkCallEntry, choice: Choice, nowMs: Long): WorkCallEntry? {
        val dealId = if (choice.choice == DealChoice.DEAL) choice.dealId else null
        // A missed incoming call takes no next call (§6).
        val next = if (!c.outgoing && !c.answered) null else choice.next
        return when (Local.of(c.localState)) {
            Local.NEW, Local.WAITING -> c.copy(
                choice = choice.choice.name,
                dealId = dealId,
                chosenByUser = true,
                outcome = choice.outcome.name,
                nextJson = next?.json()?.toString(),
                stepOneBody = null,
                // Confirmed: no need to wait out the window. A call not yet
                // looked up keeps its place; the lookup goes first.
                dueAtMs = nowMs,
            )
            Local.SENT -> {
                val status = c.serverJson?.let { ApiModels.call(JSONObject(it)).status }
                if (status != CallStatus.PENDING) null
                else c.copy(pickChoice = choice.choice.name, pickDealId = dealId, pickNextJson = next?.json()?.toString(), dueAtMs = nowMs)
            }
            else -> null
        }
    }
}
