package com.jemcik.jemrec.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jemcik.jemrec.BuildConfig
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.crm.CrmAccount
import com.jemcik.jemrec.crm.CrmActions
import com.jemcik.jemrec.crm.CrmState
import com.jemcik.jemrec.crm.CrmSync
import com.jemcik.jemrec.crm.CrmSyncWorker
import com.jemcik.jemrec.crm.GoogleSignIn
import com.jemcik.jemrec.crm.SessionStore
import com.jemcik.jemrec.crm.WorkCallMachine
import com.jemcik.jemrec.crm.WorkLine
import com.jemcik.jemrec.crm.api.ApiFailure
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.ApiResult
import com.jemcik.jemrec.crm.api.Bodies
import com.jemcik.jemrec.crm.api.Notice
import com.jemcik.jemrec.crm.db.CrmDatabase
import com.jemcik.jemrec.crm.db.WorkCallEntry
import com.jemcik.jemrec.summary.SummaryModel
import com.jemcik.jemrec.summary.SummaryWorker
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.TranscriptionWorker
import com.jemcik.jemrec.transcribe.model.ModelRepository
import com.jemcik.jemrec.transcripts.TranscriptRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Settings > Sales Engine shows. */
data class SalesEngineUi(
    val state: CrmState = CrmState.NOT_CONNECTED,
    /**
     * The server's host, shown on a stand build; empty when the build has none. In the state, not
     * read by the screen: the screen tests drew whatever local.properties held (2026-11-04).
     */
    val serverHost: String = "",
    val name: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    /** Why the last sign-in or agreement did not go through, in plain words. */
    val message: String? = null,
    val noticeAgreedAtMs: Long? = null,
    /** The notice being read, once fetched. */
    val notice: Notice? = null,
    val reading: Boolean = false,
    val sims: List<WorkLine.Sim> = emptyList(),
    val line: WorkLine.Sim? = null,
    val phoneStateAllowed: Boolean = false,
    val callLogAllowed: Boolean = false,
    /** Why the CRM will get calls without a summary, or null when it will get them. */
    val contentWarning: String? = null,
    /** The work call whose sheet is open (CallSheet), from its notification or row. */
    val sheet: CallSheetUi? = null,
    /** The open sheet's deal search, for a call waiting for its lead. */
    val search: DealSearchUi = DealSearchUi(),
)

/**
 * Settings > Sales Engine: sign-in, the notice, the work line
 * (specs/002, contracts/ui.md). The screen draws [state]; everything it does
 * goes through here.
 */
class SalesEngineController(
    private val app: Application,
    private val scope: CoroutineScope,
    /** Where the watch of the work calls runs: as long as the screen's owner, never done. */
    watchScope: CoroutineScope = scope,
) {

    private val _state = MutableStateFlow(SalesEngineUi())
    val state: StateFlow<SalesEngineUi> = _state.asStateFlow()

    private val store: SessionStore get() = CrmSync.store(app)

    private val account: CrmAccount
        get() = CrmAccount(
            CrmSync.client(app),
            store,
            CrmDatabase.of(app).calls(),
            CrmAccount.Device("${Build.MANUFACTURER} ${Build.MODEL}", BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT),
        )

    /** Reads everything again: on opening the screen and after a permission answer. */
    fun refresh() {
        if (pinned) return
        val store = store
        val sims = WorkLine.sims(app)
        val line = WorkLine.chosen(sims, store.workSubscriptionId)
        // A single SIM is chosen for the seller (FR-005): kept, so it stays the line if a second goes in.
        if (line != null && store.workSubscriptionId == null) store.setWorkLine(line.subscriptionId)
        val callLog = granted(Manifest.permission.READ_CALL_LOG)
        val state = CrmState.of(
            // A build with no address sends nothing; one with no Google client id can still use a stand token set by hand.
            configured = BuildConfig.SALES_ENGINE_URL.isNotBlank() && (GoogleSignIn.configured || store.session() != null),
            session = store.state,
            noticeOk = store.noticeOk,
            callLogAllowed = callLog,
            lineChosen = line != null,
        )
        if (state == CrmState.CONNECTED) {
            store.markSetUp(System.currentTimeMillis())
            // Whatever the seller was asked to fix is fixed: the prompt goes, the queue goes on.
            com.jemcik.jemrec.crm.CrmNotifications.cancelPrompt(app)
            CrmSyncWorker.now(app)
        }
        val session = store.session().takeIf { store.state != SessionStore.State.SIGNED_OUT }
        _state.update {
            it.copy(
                state = state,
                name = session?.userName,
                email = session?.userEmail?.takeIf(String::isNotBlank),
                noticeAgreedAtMs = store.noticeAcknowledgedAtMs.takeIf { store.noticeOk },
                sims = sims,
                line = line,
                phoneStateAllowed = WorkLine.canList(app),
                callLogAllowed = callLog,
                contentWarning = contentWarning(),
                serverHost = BuildConfig.SALES_ENGINE_URL.removePrefix("https://").substringBefore('/'),
            )
        }
    }

    fun signIn(activity: Activity) = scope.launch {
        _state.update { it.copy(busy = true, message = null) }
        val message = when (val google = GoogleSignIn.withButton(activity)) {
            is GoogleSignIn.Result.Token -> when (val r = account.signIn(google.idToken)) {
                is CrmAccount.SignIn.Done -> null
                is CrmAccount.SignIn.Refused -> r.message
                CrmAccount.SignIn.Blocked -> "LANARS blocked this phone. Contact LANARS."
                is CrmAccount.SignIn.UpgradeRequired -> "Update the app to sign in${r.minimum?.let { " (version $it or later)" } ?: ""}."
            }
            GoogleSignIn.Result.Cancelled -> null
            GoogleSignIn.Result.NoAccount -> "No Google account to sign in with on this phone."
            GoogleSignIn.Result.NotConfigured -> "This build has no Google client id yet."
            is GoogleSignIn.Result.Failed -> "Google sign-in did not work. Try again."
        }
        _state.update { it.copy(busy = false, message = message) }
        refresh()
        if (message == null && !store.noticeOk && store.state == SessionStore.State.ACTIVE) openNotice()
    }

    /**
     * A new session before this one runs out (§2.3: under 7 days left), with
     * no chooser; called when the app's screen opens, since Credential
     * Manager needs an activity. When it cannot, the seller is asked when the
     * session has actually ended.
     */
    fun renewIfDue(activity: Activity) = scope.launch {
        if (!account.needsRenewal()) return@launch
        val google = GoogleSignIn.silently(activity) as? GoogleSignIn.Result.Token ?: return@launch
        account.signIn(google.idToken)
        refresh()
    }

    /** Takes calls the call log shows since the last look, and sends what is due. */
    fun sweep() {
        com.jemcik.jemrec.crm.CrmIntakeWorker.now(app)
    }

    fun signOut() = scope.launch {
        _state.update { it.copy(busy = true, message = null) }
        account.signOut()
        _state.update { it.copy(busy = false) }
        refresh()
    }

    fun openNotice() = scope.launch {
        _state.update { it.copy(busy = true, message = null, reading = true) }
        when (val r = account.notice()) {
            is ApiResult.Ok -> _state.update { it.copy(busy = false, notice = r.value) }
            is ApiResult.Failed -> _state.update { it.copy(busy = false, reading = false, message = failureLine(r.failure)) }
        }
    }

    /** Opens a work call's sheet: its deal, outcome and next call. */
    fun openCall(id: Long) = scope.launch {
        val calls = CrmDatabase.of(app).calls()
        var c = calls.byId(id) ?: return@launch
        searchJob?.cancel()
        _state.update { it.copy(sheet = CallSheetUi.of(c, loading = true), search = DealSearchUi()) }
        // §7: a call that waited shows the deals as they are now.
        if (CallSheetUi.isPending(c) && c.serverId != null) {
            val fresh = withContext(Dispatchers.IO) { CrmSync.client(app).getCall(c.serverId!!) }
            if (fresh is ApiResult.Ok) {
                c = c.copy(serverJson = ApiModels.callJson(fresh.value).toString())
                calls.update(c)
            }
        }
        _state.update { it.copy(sheet = CallSheetUi.of(c, loading = false)) }
    }

    /**
     * The sheet's Send the transcript: the call's transcript and summary made again, queued behind
     * any run under way; when the summary is stored, ContentHook sends it (§8 takes one real
     * transcript after "failed"). Only the steps still missing are queued: a transcript there is
     * summarised, and a transcription that queues its own summary (Settings) is not given a second.
     */
    fun resendTranscript() = scope.launch {
        val sheet = _state.value.sheet ?: return@launch
        val c = CrmDatabase.of(app).calls().byId(sheet.id) ?: return@launch
        val name = c.recording ?: return@launch
        val why = withContext(Dispatchers.IO) {
            val recording = Recordings.list(app).firstOrNull { it.name == name }
                ?: return@withContext "The recording is no longer on the phone: there is nothing to transcribe."
            val model = SummaryWorker.chosenModel(app)
            if (model == null || !ModelRepository.isInstalled(app, model)) {
                return@withContext "Download a summary model in Settings → Transcripts first: the Sales Engine takes a transcript only with its summary."
            }
            // Sent already: replaced with the next revision, so made anew from the recording - the
            // transcript too, so what the transcription does better now reaches the CRM (LANARS,
            // 2026-11-05: lines of two people run together).
            val again = WorkCallMachine.resend(c)
            if (again !== c) CrmDatabase.of(app).calls().update(again)
            val transcribed = again === c && TranscriptRepository.of(app).load(name)?.lines?.isNotEmpty() == true
            if (!transcribed) TranscriptionWorker.enqueue(app, name, recording.uri, recording.caller)
            if (transcribed || !TranscribeSettings.of(app).summariseAfterTranscript) SummaryWorker.enqueue(app, name, recording.caller)
            null
        }
        _state.update { s ->
            s.copy(sheet = s.sheet?.takeIf { it.id == sheet.id }?.copy(
                canResend = why != null,
                note = why ?: "Making the transcript and its summary on the phone; they go to the Sales Engine when done.",
            ))
        }
    }

    /** Settings' Retry now: the calls waiting out a failed try go now (WorkCallMachine.retryNow). */
    fun retryNow() = scope.launch {
        val calls = CrmDatabase.of(app).calls()
        val now = System.currentTimeMillis()
        calls.all().mapNotNull { WorkCallMachine.retryNow(it, now) }.forEach { calls.update(it) }
        CrmSyncWorker.now(app)
    }

    fun closeCall() {
        searchJob?.cancel()
        _state.update { it.copy(sheet = null, search = DealSearchUi()) }
    }

    private var searchJob: Job? = null

    /**
     * The "Which lead?" search (§10), run once the seller stops typing: the
     * limit is 60 searches in 10 minutes, so not one per letter. A failed
     * search is shown, never retried by itself (§11: search, like lookup).
     */
    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < Bodies.MIN_SEARCH) {
            _state.update { it.copy(search = DealSearchUi(query = query)) }
            return
        }
        _state.update { it.copy(search = it.search.copy(query = query, searching = true, message = null)) }
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            val r = withContext(Dispatchers.IO) { CrmSync.client(app).search(q) }
            val result = when (r) {
                is ApiResult.Ok -> DealSearchUi(query, r.value, message = if (r.value.isEmpty()) "No open deal matches \"$q\"." else null)
                is ApiResult.Failed -> DealSearchUi(query, emptyList(), message = searchFailure(r.failure))
            }
            _state.update { if (it.search.query == query) it.copy(search = result) else it }
        }
    }

    private fun searchFailure(f: ApiFailure): String = when (f) {
        is ApiFailure.RetryAfter -> "Too many searches for now: try again in ${(f.seconds + 59) / 60} min, or pick a suggestion."
        ApiFailure.NoticeRequired -> "Read the Sales Engine's new notice first, in Settings."
        else -> failureLine(f).takeUnless { f == ApiFailure.Retry } ?: "The search did not go through. Check the network and try again."
    }

    /** The seller's choice from the sheet: sent at once (CrmActions). */
    fun choose(choice: CrmActions.Choice) = scope.launch {
        val id = _state.value.sheet?.id ?: return@launch
        CrmActions.choose(app, id, choice)
        closeCall()
    }

    /** Every work call, newest first, for the Calls tab's marks. */
    val workCalls: StateFlow<List<WorkCallEntry>> = CrmDatabase.of(app).calls().watch()
        .stateIn(watchScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun closeNotice() = run { if (!pinned) _state.update { it.copy(reading = false) } }

    fun agree() = scope.launch {
        val notice = _state.value.notice ?: return@launch
        _state.update { it.copy(busy = true) }
        when (val f = account.acknowledge(notice.version)) {
            null -> _state.update { it.copy(busy = false, reading = false) }
            ApiFailure.NoticeChanged -> {
                // §3.2: the version read is no longer current; show the new one.
                _state.update { it.copy(busy = false) }
                openNotice()
            }
            else -> _state.update { it.copy(busy = false, message = failureLine(f)) }
        }
        refresh()
    }

    fun chooseLine(sim: WorkLine.Sim) {
        store.setWorkLine(sim.subscriptionId)
        refresh()
    }

    private fun failureLine(f: ApiFailure): String = when (f) {
        ApiFailure.SessionDead -> "The session has ended. Sign in again."
        is ApiFailure.UpgradeRequired -> "Update the app to go on."
        else -> CrmAccount.message(f)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** FR-030a: say plainly when the CRM will get calls but no summary. */
    private fun contentWarning(): String? = when {
        ModelRepository.installedModels(app).isEmpty() && ModelRepository.importedModels(app).isEmpty() -> "No speech model on the phone: the Sales Engine gets calls without a transcript or summary."
        !TranscribeSettings.of(app).summariseAfterTranscript -> "Summaries are off: the Sales Engine gets calls without a transcript or summary."
        SummaryModel.entries.none { ModelRepository.isInstalled(app, it) } -> "No summary model on the phone: the Sales Engine gets calls without a transcript or summary."
        else -> null
    }

    private companion object {
        const val SEARCH_DELAY_MS = 400L
    }

    /** Set by the screen tests: the state shown is theirs, and nothing on the phone is read over it. */
    private var pinned = false

    internal fun showForTest(ui: SalesEngineUi) {
        pinned = true
        _state.value = ui
    }
}
