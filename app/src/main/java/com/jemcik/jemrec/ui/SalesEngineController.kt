package com.jemcik.jemrec.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jemcik.jemrec.BuildConfig
import com.jemcik.jemrec.crm.CrmAccount
import com.jemcik.jemrec.crm.CrmState
import com.jemcik.jemrec.crm.CrmSync
import com.jemcik.jemrec.crm.CrmSyncWorker
import com.jemcik.jemrec.crm.GoogleSignIn
import com.jemcik.jemrec.crm.SessionStore
import com.jemcik.jemrec.crm.WorkLine
import com.jemcik.jemrec.crm.api.ApiFailure
import com.jemcik.jemrec.crm.api.ApiResult
import com.jemcik.jemrec.crm.api.Notice
import com.jemcik.jemrec.crm.db.CrmDatabase
import com.jemcik.jemrec.summary.SummaryModel
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.model.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Settings > Sales Engine shows. */
data class SalesEngineUi(
    val state: CrmState = CrmState.NOT_CONNECTED,
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
)

/**
 * Settings > Sales Engine: sign-in, the notice, the work line
 * (specs/002, contracts/ui.md). The screen draws [state]; everything it does
 * goes through here.
 */
class SalesEngineController(private val app: Application, private val scope: CoroutineScope) {

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
            configured = GoogleSignIn.configured || store.session() != null,
            session = store.state,
            noticeOk = store.noticeOk,
            callLogAllowed = callLog,
            lineChosen = line != null,
        )
        if (state == CrmState.CONNECTED) {
            store.markSetUp(System.currentTimeMillis())
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
        if (store.state == SessionStore.State.SIGNED_OUT || store.state == SessionStore.State.BLOCKED) return
        CrmSyncWorker.now(app)
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

    /** Set by the screen tests: the state shown is theirs, and nothing on the phone is read over it. */
    private var pinned = false

    internal fun showForTest(ui: SalesEngineUi) {
        pinned = true
        _state.value = ui
    }
}
