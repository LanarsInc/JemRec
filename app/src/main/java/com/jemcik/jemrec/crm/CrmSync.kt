package com.jemcik.jemrec.crm

import android.content.Context
import com.jemcik.jemrec.BuildConfig
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.crm.WorkCallMachine.Action
import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.api.ApiFailure
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.ApiResult
import com.jemcik.jemrec.crm.api.Bodies
import com.jemcik.jemrec.crm.api.CallFacts
import com.jemcik.jemrec.crm.api.CallState
import com.jemcik.jemrec.crm.api.CallStatus
import com.jemcik.jemrec.crm.api.ContentStatus
import com.jemcik.jemrec.crm.api.DealChoice
import com.jemcik.jemrec.crm.api.NextCall
import com.jemcik.jemrec.crm.api.Outcome
import com.jemcik.jemrec.crm.api.Presentation
import com.jemcik.jemrec.crm.api.SalesEngineClient
import com.jemcik.jemrec.crm.db.CrmDatabase
import com.jemcik.jemrec.crm.db.WorkCallDao
import com.jemcik.jemrec.crm.db.WorkCallEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Sales Engine's queue, worked through in order (research R6). Every call
 * has one next thing to do, which WorkCallMachine reads off its stored state;
 * this does it, one request at a time, stores what the server said, and moves
 * on. A failure is acted on as §11 says (ApiErrors decides which it is).
 *
 * @param events what the seller is told: the after-call notification and the
 *   prompts to sign in, read the notice or update
 * @param now the clock, a parameter so tests can move it
 */
class CrmSync(
    private val calls: WorkCallDao,
    private val store: SessionStore,
    private val client: SalesEngineClient,
    private val events: Events = Events.NONE,
    private val now: () -> Long = System::currentTimeMillis,
    /** This build has an address to send to (app/build.gradle.kts); tests point it at their own server. */
    private val configured: () -> Boolean = { BuildConfig.SALES_ENGINE_URL.isNotBlank() },
) {

    interface Events {
        /** A call's state changed in a way the seller sees (the notification follows it). */
        fun changed(call: WorkCallEntry) {}
        /** Sending stopped until the seller acts. */
        fun needsSeller(state: SessionStore.State, noticeOutdated: Boolean) {}

        companion object {
            val NONE = object : Events {}
        }
    }

    /** Sending stopped for every call: the session, the notice or the version wait on the seller. */
    private class Halt : Exception()

    /**
     * Does everything due now, one request at a time.
     *
     * @return when the next thing falls due, or null when nothing waits on time
     *   (calls may still wait on the seller or on a transcript)
     */
    suspend fun runDue(): Long? = withContext(Dispatchers.IO) {
        if (!sending()) return@withContext null
        var next: Long? = null
        try {
            for (call in calls.all()) {
                val due = step(call) ?: continue
                next = if (next == null) due else minOf(next, due)
            }
        } catch (_: Halt) {
            return@withContext null
        }
        next
    }

    private fun sending(): Boolean =
        configured() && store.state == SessionStore.State.ACTIVE && store.noticeOk && store.token() != null

    /** Does [call]'s next step if it is due; returns when it next needs attention. */
    private suspend fun step(call: WorkCallEntry): Long? {
        var c = call
        // Several steps can be due at once (step 1, then its content): keep going.
        repeat(MAX_STEPS_PER_CALL) {
            val (action, due) = WorkCallMachine.next(c) ?: return null
            if (due > now()) return due
            val after = when (action) {
                Action.LOOKUP -> lookup(c)
                Action.STEP_ONE -> stepOne(c)
                Action.PICK -> pick(c)
                Action.CONTENT -> content(c)
                Action.FAIL_CONTENT -> c.copy(contentState = Content.FAILED_TO_SEND.name, contentBody = null).let { content(it) }
            }
            calls.update(after)
            if (after.localState != c.localState || after.serverJson != c.serverJson || after.lookupJson != c.lookupJson) events.changed(after)
            c = after
        }
        return now()
    }

    // --- the steps ---

    private fun lookup(c: WorkCallEntry): WorkCallEntry {
        // The hold runs from the end of the call; a seller who already
        // confirmed does not wait it out.
        val windowEnd = if (c.chosenByUser == true) c.dueAtMs else maxOf(c.dueAtMs, c.endedAtMs + WorkCallMachine.HOLD_MS)
        val waiting = c.copy(localState = Local.WAITING.name, dueAtMs = windowEnd)
        // §5: never a hidden number; a lookup that fails is not retried.
        val phone = c.phone ?: return waiting
        return when (val r = client.lookup(phone, c.countryIso)) {
            is ApiResult.Ok -> {
                val lookup = r.value
                val shown = CallDecision.shown(lookup)
                if (shown == CallDecision.Shown.Colleague) {
                    c.copy(localState = Local.INTERNAL.name, lookupJson = ApiModels.lookupJson(lookup).toString(), contentState = Content.DONE.name)
                } else {
                    // The seller may have chosen already; their choice stands.
                    val keep = c.chosenByUser == true
                    val d = CallDecision.default(lookup)
                    waiting.copy(
                        lookupJson = ApiModels.lookupJson(lookup).toString(),
                        choice = if (keep) c.choice else d.choice.name,
                        dealId = if (keep) c.dealId else d.dealId,
                        chosenByUser = if (keep) true else d.chosenByUser,
                    )
                }
            }
            is ApiResult.Failed -> when (r.failure) {
                ApiFailure.SessionDead, ApiFailure.NoticeRequired, is ApiFailure.UpgradeRequired, ApiFailure.Forbidden -> halt(r.failure)
                else -> waiting
            }
        }
    }

    private fun stepOne(c: WorkCallEntry): WorkCallEntry {
        if (WorkCallMachine.tooOld(c, now())) return c.copy(localState = Local.NOT_SENT.name, lastError = "too old", contentState = Content.DONE.name)
        // Built once and resent unchanged: the server compares retries with it (§6.3).
        val body = c.stepOneBody ?: Bodies.call(facts(c))
        val c1 = c.copy(stepOneBody = body)
        return when (val r = client.postCall(body)) {
            is ApiResult.Ok -> adopt(c1, r.value)
            is ApiResult.Failed -> when (r.failure) {
                ApiFailure.DropNext ->
                    if (JSONObject(body).has("next")) c1.copy(stepOneBody = Bodies.withoutNext(body), lastError = "next refused")
                    else c1.copy(localState = Local.NOT_SENT.name, lastError = "refused", contentState = Content.DONE.name)
                // A 409 on step 1 means the server holds other facts under this id; with no
                // call id, there is no call to fetch (§6.3). Bodies never change, so this
                // would be a bug: kept visible.
                ApiFailure.Adopt -> c1.copy(localState = Local.NOT_SENT.name, lastError = "duplicate", contentState = Content.DONE.name)
                is ApiFailure.Bug -> c1.copy(localState = Local.NOT_SENT.name, lastError = (r.failure as ApiFailure.Bug).detail ?: "refused", contentState = Content.DONE.name)
                else -> failed(c1, r.failure)
            }
        }
    }

    private fun pick(c: WorkCallEntry): WorkCallEntry {
        val id = c.serverId ?: return c.copy(pickChoice = null)
        val choice = DealChoice.entries.firstOrNull { it.name == c.pickChoice } ?: return c.copy(pickChoice = null)
        val body = Bodies.pick(choice, c.pickDealId, NextCall.fromJson(c.pickNextJson?.let(::JSONObject)))
        return when (val r = client.pickDeal(id, body)) {
            is ApiResult.Ok -> adopt(c.copy(pickChoice = null, pickDealId = null, pickNextJson = null), r.value)
            is ApiResult.Failed -> when (r.failure) {
                ApiFailure.DropNext -> c.copy(pickNextJson = null)
                ApiFailure.Adopt -> refetch(c.copy(pickChoice = null, pickDealId = null, pickNextJson = null))
                is ApiFailure.Bug -> c.copy(pickChoice = null, pickDealId = null, pickNextJson = null, lastError = (r.failure as ApiFailure.Bug).detail)
                else -> failed(c, r.failure)
            }
        }
    }

    private fun content(c: WorkCallEntry): WorkCallEntry {
        val id = c.serverId ?: return c
        val state = Content.of(c.contentState)
        val body = if (state == Content.FAILED_TO_SEND) Bodies.failed() else c.contentBody ?: return c.copy(contentState = Content.FAILED_TO_SEND.name)
        return when (val r = client.postContent(id, body)) {
            is ApiResult.Ok -> adopt(
                c.copy(contentState = if (state == Content.FAILED_TO_SEND) Content.SENT_FAILED.name else Content.SENT_READY.name),
                r.value,
            )
            is ApiResult.Failed -> when (r.failure) {
                // Other texts after ready, or failed after ready: the server keeps what it has (§8).
                ApiFailure.Adopt -> refetch(c.copy(contentState = Content.DONE.name))
                is ApiFailure.Bug -> c.copy(contentState = Content.DONE.name, lastError = (r.failure as ApiFailure.Bug).detail)
                else -> failed(c, r.failure)
            }
        }
    }

    // --- answers ---

    /** The server's call object is the truth (§6.1): kept, and the call's state follows it. */
    private fun adopt(c: WorkCallEntry, s: CallState): WorkCallEntry {
        val local = if (s.status == CallStatus.INTERNAL) Local.INTERNAL else Local.SENT
        val content = when {
            local == Local.INTERNAL -> Content.DONE
            s.status == CallStatus.PRIVATE || s.status == CallStatus.DELETED -> Content.DONE
            else -> Content.of(c.contentState)
        }
        return c.copy(
            localState = local.name,
            serverId = s.id ?: c.serverId,
            serverJson = ApiModels.callJson(s).toString(),
            contentState = content.name,
            attempts = 0,
            dueAtMs = now(),
            // Kept only to tell the seller a logged call is changed in the web app; anything else is past.
            lastError = c.lastError?.takeIf { it.startsWith("logged") },
        ).let { adopted ->
            // A content the server already has (a resend after a cut connection) is done.
            if (s.contentStatus == ContentStatus.READY && Content.of(adopted.contentState) == Content.READY_TO_SEND) adopted.copy(contentState = Content.SENT_READY.name)
            else adopted
        }
    }

    /** After a 409 duplicate: fetch the call and take the server's state (§11). */
    private fun refetch(c: WorkCallEntry): WorkCallEntry {
        val id = c.serverId ?: return c
        return when (val r = client.getCall(id)) {
            is ApiResult.Ok -> adopt(c, r.value)
            is ApiResult.Failed -> if (r.failure == ApiFailure.CallGone) gone(c) else c
        }
    }

    private fun gone(c: WorkCallEntry) = c.copy(localState = Local.NOT_SENT.name, lastError = "gone from the server", contentState = Content.DONE.name)

    /** Everything else that can go wrong with a request, as §11 says. */
    private fun failed(c: WorkCallEntry, f: ApiFailure): WorkCallEntry = when (f) {
        ApiFailure.Retry -> c.copy(attempts = c.attempts + 1, dueAtMs = now() + WorkCallMachine.backoffMs(c.attempts), lastError = "retrying")
        ApiFailure.ServerTooOld -> c.copy(attempts = c.attempts + 1, dueAtMs = now() + SERVER_TOO_OLD_WAIT_MS, lastError = "server older than the app")
        is ApiFailure.RetryAfter -> c.copy(dueAtMs = now() + f.seconds * 1000, lastError = "rate limited")
        ApiFailure.ClockAhead -> c.copy(dueAtMs = now() + CLOCK_WAIT_MS, lastError = "phone clock ahead")
        ApiFailure.CallGone -> gone(c)
        ApiFailure.SessionDead, ApiFailure.NoticeRequired, is ApiFailure.UpgradeRequired, ApiFailure.Forbidden, ApiFailure.Blocked -> halt(f)
        else -> c.copy(attempts = c.attempts + 1, dueAtMs = now() + WorkCallMachine.backoffMs(c.attempts), lastError = f::class.simpleName)
    }

    private fun halt(f: ApiFailure): Nothing {
        when (f) {
            ApiFailure.NoticeRequired -> {
                store.noticeOutdated()
                events.needsSeller(store.state, noticeOutdated = true)
            }
            is ApiFailure.UpgradeRequired -> {
                store.setState(SessionStore.State.UPGRADE_REQUIRED)
                events.needsSeller(SessionStore.State.UPGRADE_REQUIRED, noticeOutdated = false)
            }
            else -> {
                // 401 and 403 forbidden alike: sign in again, then go on (§2.3, §11).
                store.setState(SessionStore.State.NEEDS_SIGN_IN)
                events.needsSeller(SessionStore.State.NEEDS_SIGN_IN, noticeOutdated = false)
            }
        }
        throw Halt()
    }

    private fun facts(c: WorkCallEntry) = CallFacts(
        clientCallId = c.clientCallId,
        outgoing = c.outgoing,
        answered = c.answered,
        phone = c.phone,
        presentation = Presentation.valueOf(c.presentation),
        countryIso = c.countryIso,
        startedAtMs = c.startedAtMs,
        durationSeconds = c.durationSeconds,
        outcome = Outcome.entries.firstOrNull { it.name == c.outcome } ?: Outcome.REACHED,
        choice = DealChoice.entries.firstOrNull { it.name == c.choice } ?: DealChoice.AUTO,
        dealId = c.dealId,
        chosenByUser = c.chosenByUser,
        next = NextCall.fromJson(c.nextJson?.let(::JSONObject)),
        transcriptExpected = Content.of(c.contentState) != Content.NOT_EXPECTED,
    )

    companion object {
        private const val MAX_STEPS_PER_CALL = 4
        private const val SERVER_TOO_OLD_WAIT_MS = 6 * 60 * 60_000L
        private const val CLOCK_WAIT_MS = 60 * 60_000L

        fun of(context: Context): CrmSync =
            CrmSync(CrmDatabase.of(context).calls(), store(context), client(context), CrmNotifications.events(context))

        fun store(context: Context) = SessionStore(Prefs.of(context), KeystoreTokenSealer)

        fun client(context: Context): SalesEngineClient {
            val store = store(context)
            return SalesEngineClient(BuildConfig.SALES_ENGINE_URL, BuildConfig.VERSION_NAME, store::token)
        }
    }
}
