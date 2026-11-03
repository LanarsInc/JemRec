package com.jemcik.jemrec.crm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.CallLedger
import com.jemcik.jemrec.capture.CallLogLookup
import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.db.CrmDatabase
import com.jemcik.jemrec.crm.db.WorkCallEntry
import java.util.Locale
import java.util.UUID

/**
 * Takes work calls from the call log (research R5): every call on the work
 * line since setup - missed and unrecorded ones too (FR-011) - once each.
 *
 * Only Telephony's rows on the chosen SIM (FR-010, FR-015); never a row from
 * before setup or from a stretch the seller was signed out on purpose
 * (FR-046, §13); never the answering machine's test calls (FR-017).
 */
object CallIntake {

    /** A recording the phone saved, kept briefly so a call taken later can find it. */
    data class Saved(val name: String, val startMs: Long, val incoming: Boolean, val message: Boolean)

    /**
     * The new work calls among [rows]. Pure.
     *
     * @param testCalls the starts of the answering machine's test calls
     */
    fun take(
        rows: List<CallLogRows.Row>,
        line: WorkLine.Sim,
        simCount: Int,
        setupAtMs: Long,
        outWindows: List<LongRange>,
        testCalls: List<Long>,
        userId: Int,
        countryIso: String?,
        recordings: List<Saved>,
        newId: () -> String = { UUID.randomUUID().toString().lowercase(Locale.ROOT) },
    ): List<WorkCallEntry> = rows.mapNotNull { row ->
        if (row.dateMs < setupAtMs) return@mapNotNull null
        if (outWindows.any { row.dateMs in it }) return@mapNotNull null
        if (!WorkLine.matches(row.component, row.accountId, line, simCount)) return@mapNotNull null
        if (testCalls.any { CallLogLookup.sameCall(row.dateMs, it) }) return@mapNotNull null
        val f = CallLogRows.facts(row) ?: return@mapNotNull null
        val recording = if (f.answered) recordings.firstOrNull { it.incoming == !f.outgoing && CallLogLookup.sameCall(row.dateMs, it.startMs) } else null
        WorkCallEntry(
            clientCallId = newId(),
            callLogId = row.id,
            userId = userId,
            outgoing = f.outgoing,
            answered = f.answered,
            phone = f.phone,
            presentation = f.presentation.name,
            countryIso = countryIso,
            startedAtMs = f.startedAtMs,
            durationSeconds = f.durationSeconds,
            endedAtMs = f.endedAtMs,
            recording = recording?.name,
            byMachine = recording?.message == true,
            outcome = (if (recording?.message == true) com.jemcik.jemrec.crm.api.Outcome.NO_ANSWER else f.outcome).name,
            // A number to look up goes first; a hidden one waits out the Private window (§5).
            localState = (if (f.phone == null) Local.WAITING else Local.NEW).name,
            dueAtMs = if (f.phone == null) f.endedAtMs + WorkCallMachine.PRIVATE_WINDOW_MS else 0,
            contentState = (if (recording != null) Content.EXPECTED else Content.NOT_EXPECTED).name,
        )
    }

    /**
     * Reads the call log since the last look and queues the work calls in it.
     * @return how many were taken
     */
    suspend fun sweep(context: Context): Int {
        val store = CrmSync.store(context)
        val setupAt = store.setupAtMs ?: return 0
        val session = store.session() ?: return 0
        if (store.state == SessionStore.State.SIGNED_OUT || store.state == SessionStore.State.BLOCKED) return 0
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return 0
        val sims = WorkLine.sims(context)
        val line = WorkLine.chosen(sims, store.workSubscriptionId) ?: return 0
        val rows = rows(context, store.lastCallLogId, setupAt)
        if (rows.isEmpty()) return 0
        val ledger = CallLedger.read(context)
        val taken = take(
            rows, line, sims.size, setupAt, store.outWindows(), ledger.testCalls.map { it.startMillis },
            session.userId, line.countryIso, recent(context),
        )
        val calls = CrmDatabase.of(context).calls()
        var added = 0
        for (c in taken) {
            val id = calls.insert(c)
            if (id > 0) {
                added++
                CrmNotifications.show(context, c.copy(id = id))
            }
        }
        store.lastCallLogId = rows.maxOf { it.id }
        if (added > 0) Log.i("JemRec", "crm: took $added work call(s) from the call log")
        return added
    }

    private fun rows(context: Context, afterId: Long, sinceMs: Long): List<CallLogRows.Row> {
        val out = mutableListOf<CallLogRows.Row>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.NUMBER,
                CallLog.Calls.NUMBER_PRESENTATION, CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.PHONE_ACCOUNT_ID,
            ),
            "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(afterId.toString(), sinceMs.toString()),
            "${CallLog.Calls._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += CallLogRows.Row(
                    id = c.getLong(0), dateMs = c.getLong(1), durationSeconds = c.getInt(2), type = c.getInt(3),
                    number = c.getString(4), presentation = c.getInt(5), component = c.getString(6), accountId = c.getString(7),
                )
            }
        }
        return out
    }

    // --- recordings: matched whichever comes first, the call log's row or the saved file ---

    /**
     * A recording was saved: link it to its work call if that is queued, and
     * keep it a while for a call the log shows later.
     */
    suspend fun recordingSaved(context: Context, saved: Saved) {
        remember(context, saved)
        val calls = CrmDatabase.of(context).calls()
        val candidate = calls.unmatchedBetween(saved.startMs - CallLogLookup.AFTER_MS, saved.startMs + CallLogLookup.BEFORE_MS)
            .firstOrNull { it.answered && it.outgoing == !saved.incoming && CallLogLookup.sameCall(it.startedAtMs, saved.startMs) }
            ?: return
        val content = Content.of(candidate.contentState)
        calls.update(
            candidate.copy(
                recording = saved.name,
                byMachine = saved.message,
                outcome = if (saved.message && candidate.stepOneBody == null) com.jemcik.jemrec.crm.api.Outcome.NO_ANSWER.name else candidate.outcome,
                contentState = if (content == Content.NOT_EXPECTED) Content.EXPECTED.name else candidate.contentState,
            ),
        )
    }

    private const val KEY_RECENT = "crm_recent_recordings"
    private const val RECENT_KEEP_MS = 60 * 60_000L

    private fun remember(context: Context, s: Saved) {
        val now = System.currentTimeMillis()
        val kept = recent(context).filter { now - it.startMs < RECENT_KEEP_MS } + s
        Prefs.of(context).edit {
            putString(KEY_RECENT, kept.joinToString("\n") { "${it.startMs}\t${it.incoming}\t${it.message}\t${it.name}" })
        }
    }

    private fun recent(context: Context): List<Saved> =
        Prefs.of(context).getString(KEY_RECENT, null).orEmpty().lines().mapNotNull { line ->
            val p = line.split('\t', limit = 4)
            if (p.size != 4) null else p[0].toLongOrNull()?.let { Saved(p[3], it, p[1] == "true", p[2] == "true") }
        }
}
