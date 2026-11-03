package com.jemcik.jemrec.crm

import android.content.Context
import com.jemcik.jemrec.crm.WorkCallMachine.Content
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
 * What the seller does to a work call - Private, a deal, an outcome, a next
 * call - from the notification or the call sheet. Each is stored on the call
 * and left to the queue to send (CrmSync).
 */
object CrmActions {

    /** The seller's choice for a call, from the sheet. */
    data class Choice(val choice: DealChoice, val dealId: Int?, val outcome: Outcome, val next: NextCall?)

    /**
     * Private: before step 1, nothing more is sent (§13); after it, the server
     * is asked to drop the call's number and texts (§10), which it does for
     * any call not logged on a deal.
     */
    suspend fun makePrivate(context: Context, id: Long) {
        val calls = CrmDatabase.of(context).calls()
        val c = calls.byId(id) ?: return
        val updated = privateOf(c) ?: return
        calls.update(updated)
        CrmNotifications.show(context, updated)
        if (updated.privateAsked) CrmSyncWorker.now(context)
    }

    /** Pure: the call after Private, or null when it cannot be made private here. */
    fun privateOf(c: WorkCallEntry): WorkCallEntry? = when (Local.of(c.localState)) {
        Local.NEW, Local.WAITING -> c.copy(localState = Local.PRIVATE.name, contentState = Content.DONE.name, stepOneBody = null)
        Local.SENT -> {
            val status = c.serverJson?.let { ApiModels.call(JSONObject(it)).status }
            if (status == CallStatus.LOGGED || status == CallStatus.PRIVATE) null
            else c.copy(privateAsked = true, dueAtMs = 0)
        }
        else -> null
    }

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
