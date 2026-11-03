package com.jemcik.jemrec.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedButton
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
import com.jemcik.jemrec.crm.WorkCallMachine.Local
import com.jemcik.jemrec.crm.api.ApiModels
import com.jemcik.jemrec.crm.api.CallStatus
import com.jemcik.jemrec.crm.api.Candidate
import com.jemcik.jemrec.crm.api.DealChoice
import com.jemcik.jemrec.crm.api.DealStatus
import com.jemcik.jemrec.crm.api.NextCall
import com.jemcik.jemrec.crm.api.Outcome
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
    /** "Private" before step 1, "Make private" after; null when it cannot be. */
    val privateLabel: String?,
    /** Why something cannot be done here, e.g. a logged call is edited in the web app. */
    val note: String?,
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
                where = markText(c),
                canChoose = (before || pending) && !loading,
                beforeSending = before,
                candidates = candidates,
                candidatesTotal = total,
                preselected = preselected,
                outcome = Outcome.entries.firstOrNull { it.name == c.outcome } ?: Outcome.REACHED,
                next = NextCall.fromJson(c.nextJson?.let(::JSONObject)),
                privateLabel = when {
                    before -> "Private"
                    local == Local.SENT && !logged && server?.status != CallStatus.PRIVATE && server?.status != CallStatus.DELETED -> "Make private"
                    else -> null
                },
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
                Local.PRIVATE -> "Private: not sent"
                Local.INTERNAL -> "A colleague: not sent"
                Local.NOT_SENT -> "Not sent to the Sales Engine${c.lastError?.let { " ($it)" } ?: ""}"
                Local.SENT -> when (server?.status) {
                    CallStatus.LOGGED -> "On ${server.dealTitle}"
                    CallStatus.PENDING -> "Choose a deal"
                    CallStatus.DECLINED -> "Not logged"
                    CallStatus.NOT_IN_CRM -> "Not in the CRM"
                    CallStatus.HIDDEN -> "Hidden number: not on a deal"
                    CallStatus.PRIVATE -> "Private"
                    CallStatus.DELETED -> "Deleted in the CRM"
                    CallStatus.INTERNAL -> "A colleague: not sent"
                    else -> "Sent"
                }
            }
        }

        fun needsSeller(c: WorkCallEntry): Boolean = isPending(c)
    }
}

/** The sheet over whatever is showing, while one is open (MainScreen). */
@Composable
internal fun CallSheetHost(vm: MainViewModel) {
    val ui by vm.salesEngine.state.collectAsStateWithLifecycle()
    val sheet = ui.sheet ?: return
    CallSheet(sheet, onChoose = vm.salesEngine::choose, onPrivate = vm.salesEngine::makePrivate, onClose = vm.salesEngine::closeCall)
}

private val OUTCOME_WORDS = linkedMapOf(
    Outcome.REACHED to "Reached",
    Outcome.NO_ANSWER to "No answer",
    Outcome.VOICEMAIL to "Voicemail",
    Outcome.WRONG_NUMBER to "Wrong number",
    Outcome.CALLBACK to "Call back",
    Outcome.NOT_INTERESTED to "Not interested",
)

/** The deal value "Don't log" (§6.1 `none`) in the radio group. */
private const val DONT_LOG = -1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallSheet(ui: CallSheetUi, onChoose: (CrmActions.Choice) -> Unit, onPrivate: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        CallSheetContent(ui, onChoose, onPrivate)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallSheetContent(ui: CallSheetUi, onChoose: (CrmActions.Choice) -> Unit, onPrivate: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var deal by remember(ui.id, ui.loading) { mutableStateOf(ui.preselected) }
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
            Text("Deal", style = MaterialTheme.typography.titleMedium)
            if (ui.loading) {
                Text("Fetching the deals as they are now…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.selectableGroup()) {
                if (ui.candidates.isEmpty() && !ui.loading) {
                    Text(
                        if (ui.phone == null) "A hidden number is not looked up: the Sales Engine decides when it is sent."
                        else "The CRM has no deal for this number: the Sales Engine decides when it is sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.candidates.forEach { c ->
                    DealRow(
                        selected = deal == c.dealId,
                        title = c.title,
                        lines = listOfNotNull(c.orgName, c.stageName, c.ownerName).joinToString(" · "),
                        status = c.status.takeIf { it != DealStatus.OPEN }?.name?.lowercase()?.replaceFirstChar { it.titlecase() },
                        due = c.hasDueCall,
                    ) { haptics.tap(); deal = c.dealId }
                }
                if (ui.candidates.isNotEmpty()) {
                    DealRow(selected = deal == DONT_LOG, title = "Don't log", lines = "Keep this call outside the CRM", status = null, due = false) {
                        haptics.tap(); deal = DONT_LOG
                    }
                }
            }
            if (ui.candidatesTotal > ui.candidates.size) {
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

        if (ui.canChoose && !ui.missedIncoming && deal != DONT_LOG) {
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

        if (ui.canChoose || ui.privateLabel != null) {
            ButtonPair(listOfNotNull(ui.privateLabel, if (ui.canChoose) "Log call" else null)) { i, m ->
                val label = listOfNotNull(ui.privateLabel, if (ui.canChoose) "Log call" else null)[i]
                if (label == "Log call") {
                    Button(
                        onClick = {
                            haptics.tap()
                            val choice = when (deal) {
                                null -> DealChoice.AUTO
                                DONT_LOG -> DealChoice.NONE
                                else -> DealChoice.DEAL
                            }
                            val next = if (planning && choice != DealChoice.NONE && !ui.missedIncoming) {
                                NextCall(date.toString(), time?.let(OsloTime::formatTime), subject.trim().ifEmpty { null })
                            } else {
                                null
                            }
                            onChoose(CrmActions.Choice(choice, deal?.takeIf { it != DONT_LOG }, outcome, next))
                        },
                        enabled = !ui.loading,
                        modifier = m,
                    ) { ButtonLabel("Log call") }
                } else {
                    OutlinedButton(onClick = { haptics.tap(); onPrivate() }, modifier = m) { ButtonLabel(label) }
                }
            }
        }
    }
}

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

/** A work call's state under its row in the Calls tab; a tap opens its sheet. */
@Composable
internal fun CrmMark(c: WorkCallEntry, onOpen: (Long) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val attention = CallSheetUi.needsSeller(c)
    Text(
        "Sales Engine · ${CallSheetUi.markText(c)}",
        style = MaterialTheme.typography.labelMedium,
        color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { haptics.tap(); onOpen(c.id) }
            .padding(start = 72.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
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
