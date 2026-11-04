package com.jemcik.jemrec.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jemcik.jemrec.crm.CrmActions
import com.jemcik.jemrec.crm.OsloTime
import com.jemcik.jemrec.crm.WorkCallMachine.Content
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.CallStatus
import com.jemcik.jemrec.crm.api.Candidate
import com.jemcik.jemrec.crm.api.DealChoice
import com.jemcik.jemrec.crm.api.DealStatus
import com.jemcik.jemrec.crm.api.NextCall
import com.jemcik.jemrec.crm.api.Outcome
import com.jemcik.jemrec.crm.api.PendingReason
import com.jemcik.jemrec.crm.db.WorkCallEntry
import org.json.JSONObject
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale

/** What the call sheet shows of one work call (contracts/ui.md "The call sheet"). */
data class CallSheetUi(
    val id: Long,
    val outgoing: Boolean,
    val answered: Boolean,
    val phone: String?,
    val startedAtMs: Long,
    val durationSeconds: Int,
    val loading: Boolean,
    /** Where it stands, in a few words: "Logging in 2 min", "Logged on …". */
    val where: String,
    /** The seller can choose a deal (before step 1, or while the call waits for one). */
    val canChoose: Boolean,
    /** Outcome and next call can still be set (before step 1). */
    val beforeSending: Boolean,
    val candidates: List<Candidate>,
    val candidatesTotal: Int,
    /** The deal ticked when the sheet opens; null for none. */
    val preselected: Int?,
    val outcome: Outcome,
    val next: NextCall?,
    /** Why something cannot be done here, e.g. a logged call is edited in the web app. */
    val note: String?,
    /**
     * The server answered `no_match` (API of 2026-11-03): the seller has a
     * weekly goal and the number has no deal. The candidates are suggestions,
     * and any open deal found by searching may be picked (§7, §10).
     */
    val whichLead: Boolean = false,
) {
    val missedIncoming: Boolean get() = !outgoing && !answered

    companion object {
        fun isPending(c: WorkCallEntry): Boolean =
            Local.of(c.localState) == Local.SENT && c.serverJson?.let { ApiModels.call(JSONObject(it)).status } == CallStatus.PENDING

        /** Pure: the sheet for [c] as it is stored now. */
        fun of(c: WorkCallEntry, loading: Boolean): CallSheetUi {
            val local = Local.of(c.localState)
            val lookup = c.lookupJson?.let { ApiModels.lookup(JSONObject(it)) }
            val server = c.serverJson?.let { ApiModels.call(JSONObject(it)) }
            val before = local == Local.NEW || local == Local.WAITING
            val pending = local == Local.SENT && server?.status == CallStatus.PENDING
            val candidates = when {
                pending -> server.candidates
                before -> lookup?.candidates.orEmpty()
                else -> emptyList()
            }
            val total = when {
                pending -> server.candidatesTotal
                before -> lookup?.candidatesTotal ?: 0
                else -> 0
            }
            val preselected = when {
                before && c.chosenByUser == true -> c.dealId
                before -> lookup?.autoDealId ?: lookup?.suggestedDealId
                // Suggestions for a lead are not the number's deals: nothing is ticked for the seller.
                pending && server.pendingReason == PendingReason.NO_MATCH -> null
                pending -> candidates.firstOrNull { it.status == DealStatus.OPEN && it.hasDueCall }?.dealId
                    ?: candidates.firstOrNull { it.status == DealStatus.OPEN }?.dealId
                else -> null
            }
            val logged = server?.status == CallStatus.LOGGED
            return CallSheetUi(
                id = c.id,
                outgoing = c.outgoing,
                answered = c.answered,
                phone = c.phone,
                startedAtMs = c.startedAtMs,
                durationSeconds = c.durationSeconds,
                loading = loading,
                where = lineText(c),
                canChoose = (before || pending) && !loading,
                beforeSending = before,
                candidates = candidates,
                candidatesTotal = total,
                preselected = preselected,
                outcome = Outcome.entries.firstOrNull { it.name == c.outcome } ?: Outcome.REACHED,
                next = NextCall.fromJson(c.nextJson?.let(::JSONObject)),
                whichLead = pending && server.pendingReason == PendingReason.NO_MATCH,
                note = when {
                    logged -> "A logged call is changed or deleted on its deal in the Sales Engine's web app."
                    c.lastError?.startsWith("logged") == true -> "Logged meanwhile: change it in the web app."
                    else -> null
                },
            )
        }

        /** A work call's state in a few words, for its row in the Calls tab and the sheet. */
        fun markText(c: WorkCallEntry): String {
            val server = c.serverJson?.let { ApiModels.call(JSONObject(it)) }
            return when (Local.of(c.localState)) {
                Local.NEW, Local.WAITING -> if (c.chosenByUser == true) "Sending to the Sales Engine…" else "Logging to the Sales Engine in 2 min"
                Local.INTERNAL -> "A colleague: not sent"
                Local.NOT_SENT -> "Not sent to the Sales Engine${c.lastError?.let { " ($it)" } ?: ""}"
                Local.SENT -> when (server?.status) {
                    CallStatus.LOGGED -> "On ${server.dealTitle}"
                    CallStatus.PENDING -> if (server.pendingReason == PendingReason.NO_MATCH) "Which lead?" else "Choose a deal"
                    CallStatus.DECLINED -> "Other: not on a deal"
                    CallStatus.NOT_IN_CRM -> "Not in the CRM"
                    CallStatus.HIDDEN -> "Hidden number: not on a deal"
                    // Only calls from before the API of 2026-11-03 can be private; none come from this app.
                    CallStatus.PRIVATE -> "Kept outside the CRM"
                    CallStatus.DELETED -> "Deleted in the CRM"
                    CallStatus.INTERNAL -> "A colleague: not sent"
                    else -> "Sent"
                }
            }
        }

        /**
         * Where the call's transcript and summary stand (§8), once the call itself reached the
         * server; null before that, and for a call with no recording. Without it a seller could
         * not tell whether a call's texts had gone (asked on the A24, 2026-11-04).
         */
        fun contentText(c: WorkCallEntry): String? {
            if (Local.of(c.localState) != Local.SENT) return null
            return when (Content.of(c.contentState)) {
                Content.NOT_EXPECTED -> null
                Content.EXPECTED -> "transcript to follow"
                Content.READY_TO_SEND -> "sending the transcript"
                Content.SENT_READY -> "transcript sent"
                // "failed" sent or to send: the CRM knows no transcript is coming (FR-031).
                Content.FAILED_TO_SEND, Content.SENT_FAILED, Content.DONE -> "transcript not sent"
            }
        }

        /** The call's line under its row and on its transcript: the call, then its texts. */
        fun lineText(c: WorkCallEntry): String = listOfNotNull(markText(c), contentText(c)).joinToString(" · ")

        fun needsSeller(c: WorkCallEntry): Boolean = isPending(c)
    }
}

/** The sheet over whatever is showing, while one is open (MainScreen). */
@Composable
internal fun CallSheetHost(vm: MainViewModel) {
    val ui by vm.salesEngine.state.collectAsStateWithLifecycle()
    val sheet = ui.sheet ?: return
    CallSheet(sheet, ui.search, onChoose = vm.salesEngine::choose, onSearch = vm.salesEngine::search, onClose = vm.salesEngine::closeCall)
}

/** The deal search of the "Which lead?" picker (§10), as the controller has it now. */
data class DealSearchUi(
    val query: String = "",
    val deals: List<Candidate> = emptyList(),
    val searching: Boolean = false,
    /** Why there are no results: none matched, the limit, no network. */
    val message: String? = null,
)

private val OUTCOME_WORDS = linkedMapOf(
    Outcome.REACHED to "Reached",
    Outcome.NO_ANSWER to "No answer",
    Outcome.VOICEMAIL to "Voicemail",
    Outcome.WRONG_NUMBER to "Wrong number",
    Outcome.CALLBACK to "Call back",
    Outcome.NOT_INTERESTED to "Not interested",
)

/** The deal value Other (§6.1 `none`, "Don't log" until the API of 2026-11-03) in the radio group. */
private const val OTHER = -1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallSheet(ui: CallSheetUi, search: DealSearchUi, onChoose: (CrmActions.Choice) -> Unit, onSearch: (String) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        CallSheetContent(ui, onChoose, search = search, onSearch = onSearch)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallSheetContent(
    ui: CallSheetUi,
    onChoose: (CrmActions.Choice) -> Unit,
    modifier: Modifier = Modifier,
    search: DealSearchUi = DealSearchUi(),
    onSearch: (String) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    var deal by remember(ui.id, ui.loading) { mutableStateOf(ui.preselected) }
    // A deal picked from the search stays on screen when the search changes.
    var found by remember(ui.id) { mutableStateOf<Candidate?>(null) }
    var outcome by remember(ui.id) { mutableStateOf(ui.outcome) }
    var planning by remember(ui.id) { mutableStateOf(ui.next != null) }
    var date by remember(ui.id) { mutableStateOf(ui.next?.dueDate?.let(LocalDate::parse) ?: OsloTime.earliestNextDate(ui.startedAtMs).plusDays(1)) }
    var time by remember(ui.id) { mutableStateOf(ui.next?.dueTime?.let(OsloTime::parseTime)) }
    var subject by remember(ui.id) { mutableStateOf(ui.next?.subject.orEmpty()) }
    val context = LocalContext.current

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(ui.phone ?: "Hidden number", style = MaterialTheme.typography.titleLarge)
        val kind = when {
            ui.outgoing && ui.answered -> "Outgoing"
            ui.outgoing -> "Outgoing, not answered"
            ui.answered -> "Incoming"
            else -> "Missed"
        }
        val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ui.startedAtMs))
        val length = if (ui.durationSeconds > 0) " · %d:%02d".format(Locale.US, ui.durationSeconds / 60, ui.durationSeconds % 60) else ""
        Text("$kind · $at$length", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ui.where, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        ui.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        if (ui.canChoose) {
            HorizontalDivider()
            Text(if (ui.whichLead) "Lead" else "Deal", style = MaterialTheme.typography.titleMedium)
            if (ui.whichLead) {
                Text(
                    "This number is on no deal. Pick the lead this call was for, or search for it: the Sales Engine saves the number on it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.loading) {
                Text("Fetching the deals as they are now…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.selectableGroup()) {
                if (ui.whichLead) {
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = onSearch,
                        label = { Text("Search deals or companies") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val line = when {
                        search.searching -> "Searching…"
                        search.message != null -> search.message
                        else -> null
                    }
                    line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                    val shown = search.deals.map { it.dealId }.toSet() + ui.candidates.map { it.dealId }
                    found?.takeIf { it.dealId !in shown }?.let { c -> CandidateRow(c, deal == c.dealId) { haptics.tap(); deal = c.dealId } }
                    search.deals.forEach { c ->
                        CandidateRow(c, deal == c.dealId) { haptics.tap(); deal = c.dealId; found = c }
                    }
                    if (ui.candidates.isNotEmpty()) {
                        Text("Suggestions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                } else if (ui.candidates.isEmpty() && !ui.loading) {
                    Text(
                        if (ui.phone == null) "A hidden number is not looked up: the Sales Engine decides when it is sent."
                        else "The CRM has no deal for this number: the Sales Engine decides when it is sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.candidates.filter { c -> !ui.whichLead || c.dealId !in search.deals.map { it.dealId } }.forEach { c ->
                    CandidateRow(c, deal == c.dealId) { haptics.tap(); deal = c.dealId }
                }
                if (ui.candidates.isNotEmpty() || ui.whichLead) {
                    DealRow(selected = deal == OTHER, title = "Other", lines = "Not on a deal: kept outside the CRM", status = null, due = false) {
                        haptics.tap(); deal = OTHER
                    }
                }
            }
            // A no_match call's total counts its suggestions, not more deals (§6.2).
            if (!ui.whichLead && ui.candidatesTotal > ui.candidates.size) {
                val more = ui.candidatesTotal - ui.candidates.size
                Text(
                    "$more more ${if (more == 1) "deal" else "deals"}: log this call in the web app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.beforeSending) {
            HorizontalDivider()
            Text("Outcome", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OUTCOME_WORDS.forEach { (o, words) ->
                    FilterChip(selected = outcome == o, onClick = { haptics.tap(); outcome = o }, label = { Text(words) })
                }
            }
        }

        if (ui.canChoose && !ui.missedIncoming && deal != OTHER) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Plan the next call", style = MaterialTheme.typography.titleMedium)
                    Text("On the same deal, in Norwegian time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = planning, onCheckedChange = { haptics.toggle(it); planning = it })
            }
            if (planning) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            val earliest = OsloTime.earliestNextDate(ui.startedAtMs)
                            DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).let { if (it.isBefore(earliest)) earliest else it } }, date.year, date.monthValue - 1, date.dayOfMonth)
                                .apply { datePicker.minDate = earliest.atStartOfDay(OsloTime.ZONE).toInstant().toEpochMilli() }
                                .show()
                        },
                        label = { Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date.atStartOfDay(OsloTime.ZONE).toInstant().toEpochMilli() + 12 * 3_600_000))) },
                    )
                    AssistChip(
                        onClick = {
                            val t = time ?: LocalTime.of(10, 0)
                            TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m) }, t.hour, t.minute, true).show()
                        },
                        label = { Text(time?.let(OsloTime::formatTime) ?: "Any time") },
                    )
                }
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it.take(NextCall.MAX_SUBJECT) },
                    label = { Text("Subject") },
                    placeholder = { Text("Follow up") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (ui.canChoose) {
            Button(
                onClick = {
                    haptics.tap()
                    val choice = when (deal) {
                        null -> DealChoice.AUTO
                        OTHER -> DealChoice.NONE
                        else -> DealChoice.DEAL
                    }
                    val next = if (planning && choice != DealChoice.NONE && !ui.missedIncoming) {
                        NextCall(date.toString(), time?.let(OsloTime::formatTime), subject.trim().ifEmpty { null })
                    } else {
                        null
                    }
                    onChoose(CrmActions.Choice(choice, deal?.takeIf { it != OTHER }, outcome, next))
                },
                // A waiting call needs a deal or Other: sending it undecided again would change nothing.
                enabled = !ui.loading && (ui.beforeSending || deal != null),
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonLabel("Log call") }
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, selected: Boolean, onSelect: () -> Unit) = DealRow(
    selected = selected,
    title = c.title,
    lines = listOfNotNull(c.orgName, c.stageName, c.ownerName).joinToString(" · "),
    status = c.status.takeIf { it != DealStatus.OPEN }?.name?.lowercase()?.replaceFirstChar { it.titlecase() },
    due = c.hasDueCall,
    onSelect = onSelect,
)

@Composable
private fun DealRow(selected: Boolean, title: String, lines: String, status: String?, due: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val extra = listOfNotNull(status, if (due) "call due" else null).joinToString(" · ")
            Text(
                listOf(lines, extra).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A work call's state under its row in the Calls and Recordings tabs, and on its transcript; a tap
 * opens its sheet. [padding] lines it up with what it is under: a row's text, past its avatar.
 */
@Composable
internal fun CrmMark(
    c: WorkCallEntry,
    onOpen: (Long) -> Unit,
    padding: PaddingValues = PaddingValues(start = 72.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
) {
    val haptics = LocalHapticFeedback.current
    val attention = CallSheetUi.needsSeller(c)
    Text(
        "Sales Engine · ${CallSheetUi.lineText(c)}",
        style = MaterialTheme.typography.labelMedium,
        color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { haptics.tap(); onOpen(c.id) }
            .padding(padding),
    )
}

/** Over the Calls tab: the calls that wait for the seller to choose a deal. */
@Composable
internal fun WaitingForDeal(waiting: List<WorkCallEntry>, onOpen: (Long) -> Unit) {
    if (waiting.isEmpty()) return
    TipCard(
        if (waiting.size == 1) "A call waits for you to choose its deal. Tap its Sales Engine line."
        else "${waiting.size} calls wait for you to choose their deal. Tap their Sales Engine lines.",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onOpen(waiting.first().id) },
    )
}
