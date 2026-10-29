package com.jemcik.jemrec.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jemcik.jemrec.summary.TranscriptTimestamp
import com.jemcik.jemrec.transcribe.LanguageNames
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscriptionLanguageChoice
import com.jemcik.jemrec.transcribe.TranscriptSegment
import com.jemcik.jemrec.transcripts.Transcript
import java.util.Locale

/**
 * A recording's transcript: what is needed before there is one (the speech
 * model, then a run), the run while it goes, and then the call as lines -
 * who said each, when, and a tap to hear it from there.
 *
 * One screen for every look, reached from the row's Transcript button, the
 * way Recently deleted is reached from Settings: it carries no player of its
 * own, so the three looks' players stay as they are. A tap on a line plays
 * the recording from that moment, and the system's media controls (and the
 * row, back on the list) show it playing.
 */
@Composable
fun TranscriptScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val ui by vm.transcripts.state.collectAsStateWithLifecycle()
    TranscriptContent(ui, state.playingName, vm, modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptContent(ui: TranscriptUi, playingName: String?, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val recording = ui.recording ?: return
    val transcript = ui.transcript
    val list = rememberLazyListState()
    // A tap on a summary point: to the transcript, at the first line said by
    // then. The lines start after the three items above them.
    LaunchedEffect(ui.jumpToMs, ui.tab) {
        val ms = ui.jumpToMs ?: return@LaunchedEffect
        if (ui.tab != TranscriptTab.TRANSCRIPT || transcript == null) return@LaunchedEffect
        val index = transcript.lines.indexOfLast { it.startMs <= ms + 500 }.coerceAtLeast(0)
        list.animateScrollToItem(HEAD_ITEMS + index)
        vm.transcripts.jumped()
    }
    LazyColumn(state = list, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recording.caller ?: "Call", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${recording.whenLabel} · ${recording.durationLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            // Transcript | Summary, Material 3's connected button group as the
            // rest of the app picks one of two (ConnectedChoices).
            ConnectedChoices(
                choices = TranscriptTab.entries,
                selected = ui.tab,
                label = { if (it == TranscriptTab.TRANSCRIPT) "Transcript" else "Summary" },
                onChoose = vm.transcripts::showTab,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (ui.tab == TranscriptTab.SUMMARY) {
            item { SummaryPart(ui, vm) }
            return@LazyColumn
        }
        item { Column { LanguageLine(ui, vm); Status(ui, vm) } }
        if (transcript != null) {
            if (transcript.lines.isEmpty()) {
                item {
                    Text(
                        "No speech was found in this recording.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(transcript.lines, key = { i, _ -> i }) { i, line ->
                if (i > 0 && line.speaker != transcript.lines[i - 1].speaker) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Line(
                    line = line,
                    named = transcript.facts.split,
                    modifier = Modifier.combinedClickable(
                        onClick = { haptics.tap(); vm.playFrom(recording, line.startMs) },
                        onLongClick = { clipboard.setText(AnnotatedString(line.text)); vm.notice("Line copied") },
                        onClickLabel = "Play from here",
                        onLongClickLabel = "Copy the line",
                    ),
                )
            }
            item { Facts(transcript, playingName == recording.name) }
            item {
                if (ui.job == JobState.Idle && ui.model is ModelState.Ready) {
                    TextButton(onClick = { haptics.tap(); vm.transcripts.transcribe() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Transcribe again")
                    }
                }
            }
        }
    }
}

/** The items above a transcript's lines: the call, the tabs, the language and status. */
private const val HEAD_ITEMS = 3

/**
 * The summary half: what is needed first (a transcript, the model, a run),
 * the run while it goes, then the summary - why the call happened, what was
 * said, and the points, decisions, things to do and facts, each with the time
 * it was said, which a tap takes back to in the transcript.
 */
@Composable
private fun SummaryPart(ui: TranscriptUi, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    val body = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    val summary = ui.summary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val job = ui.summaryJob) {
            JobState.Queued -> Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Waiting for the work before it to finish.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { haptics.tap(); vm.transcripts.cancelSummary() }) { Text("Cancel") }
            }
            is JobState.Running -> Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (job.percent == null) "Reading the call…" else "Summarising on the phone… ${job.percent}%", style = MaterialTheme.typography.bodyMedium)
                if (job.percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { haptics.tap(); vm.transcripts.cancelSummary() }) { Text("Cancel") }
            }
            is JobState.Failed -> Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The summary did not finish: ${job.reason}", color = MaterialTheme.colorScheme.error)
                Button(onClick = { haptics.tap(); vm.transcripts.summarise() }) { Text("Try again") }
            }
            JobState.Idle -> when {
                ui.transcript == null -> Text("A summary is made from the transcript. Transcribe the call first.", style = MaterialTheme.typography.bodyMedium, modifier = body)
                ui.transcript.lines.isEmpty() -> Text("No speech was found, so there is nothing to summarise.", style = MaterialTheme.typography.bodyMedium, modifier = body)
                summary == null -> SummaryModelOrStart(ui, vm, body)
            }
        }
        if (summary != null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summary.intent.isNotBlank()) Text(summary.intent, style = MaterialTheme.typography.titleMedium)
                Text(summary.summary, style = MaterialTheme.typography.bodyLarge)
                Points("Key points", summary.keyPoints, vm)
                Points("Decisions", summary.decisions, vm)
                Points("To do", summary.actionItems, vm)
                Points("Facts", summary.keyFacts, vm)
                Text(
                    listOfNotNull(ui.summaryModelId, ui.summaryWallMs?.let { "took ${spoken(it)}" }).joinToString(", ") +
                        ". Made on the phone from the transcript; check what matters against it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.summaryJob == JobState.Idle && ui.summaryModel is SummaryModelState.Ready) {
                    TextButton(onClick = { haptics.tap(); vm.transcripts.summarise() }) { Text("Summarise again") }
                }
            }
        }
    }
}

@Composable
private fun SummaryModelOrStart(ui: TranscriptUi, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val m = ui.summaryModel) {
            SummaryModelState.Checking -> Unit
            is SummaryModelState.TooSmall -> Text(
                "Summaries need a phone with ${m.needsGb} GB of memory or more; this one has less.",
                style = MaterialTheme.typography.bodyMedium,
            )
            is SummaryModelState.Missing -> {
                Text(
                    "Summaries are written on the phone by a language model, ${m.model.label}: " +
                        "${sizeMb(m.model.sizeBytes)} to download once, and about ${sizeMb(m.model.peakMemoryBytes)} of memory while it works.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { haptics.tap(); vm.transcripts.downloadSummaryModel() }) {
                    Text(if (m.partialBytes > 0) "Continue the download" else "Download ${sizeMb(m.model.sizeBytes)}")
                }
            }
            is SummaryModelState.Downloading -> {
                Text("Downloading ${m.model.label}… ${m.percent}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { m.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is SummaryModelState.DownloadFailed -> {
                Text(m.reason, color = MaterialTheme.colorScheme.error)
                Button(onClick = { haptics.tap(); vm.transcripts.downloadSummaryModel() }) { Text("Try again") }
            }
            is SummaryModelState.Ready -> {
                Text("With ${m.model.label}, on the phone.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { haptics.tap(); vm.transcripts.summarise() }) { Text("Summarise") }
            }
        }
    }
}

/** A list of the summary's: each item with its time, when the model gave one, as a link to the line. */
@Composable
private fun Points(title: String, items: List<String>, vm: MainViewModel) {
    if (items.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        items.forEach { item ->
            val stamp = TranscriptTimestamp.parseLeading(item)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (stamp != null) {
                            Modifier.clickable(onClickLabel = "Show in the transcript") { haptics.tap(); vm.transcripts.jumpTo(stamp.millis) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stamp?.let { lineTime(it.millis) } ?: "•",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (stamp != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                )
                Text(stamp?.text ?: item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The language this call is transcribed in, and the way to change it for this
 * call alone: a phone takes calls in more than one language, and one pinned
 * for every call makes the others come back as nonsense (CallVault's reason
 * for the same choice).
 */
@Composable
private fun LanguageLine(ui: TranscriptUi, vm: MainViewModel) {
    var choosing by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val code = ui.language ?: TranscriptionLanguageChoice.AUTO
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Language: ${LanguageNames.both(code)}" + if (ui.languageOverride == null) "" else " (this call)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { haptics.tap(); choosing = true }) { Text("Change") }
    }
    if (choosing) {
        // null first: back to whatever Settings says.
        val choices: List<String?> = listOf(null, TranscriptionLanguageChoice.AUTO) + LanguageNames.offered
        ChoiceDialog(
            title = "Language of this call",
            choices = choices,
            selected = ui.languageOverride,
            label = { it?.let(LanguageNames::own) ?: "As in Settings" },
            detail = { c -> if (c == null) "The language set in Settings" else LanguageNames.english(c) ?: "Slower, and sometimes wrong" },
            intro = "For this call only. Transcribe it again to use it.",
            onChoose = { vm.transcripts.setLanguage(it) },
            onDismiss = { choosing = false },
        )
    }
}

/** What stands between the user and a transcript, or the run under way. */
@Composable
private fun Status(ui: TranscriptUi, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    val body = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    when (val job = ui.job) {
        JobState.Queued -> Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Waiting for another transcription to finish.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { haptics.tap(); vm.transcripts.cancel() }) { Text("Cancel") }
        }
        is JobState.Running -> Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (job.percent == null) "Getting ready…" else "Transcribing on the phone… ${job.percent}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (job.percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text(
                "It goes on with the screen off. You can leave this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { haptics.tap(); vm.transcripts.cancel() }) { Text("Cancel") }
        }
        is JobState.Failed -> Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The transcription did not finish: ${job.reason}", color = MaterialTheme.colorScheme.error)
            Button(onClick = { haptics.tap(); vm.transcripts.transcribe() }) { Text("Try again") }
        }
        JobState.Idle -> if (ui.transcript == null) ModelOrStart(ui, vm, body)
    }
}

@Composable
private fun ModelOrStart(ui: TranscriptUi, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val model = ui.model) {
            ModelState.Checking -> Unit
            is ModelState.Missing -> {
                Text(
                    "Transcripts are made on the phone; the call never leaves it. " +
                        "That needs the speech model ${model.model.label} once: ${sizeMb(model.bytes)}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { haptics.tap(); vm.transcripts.downloadModel() }) {
                    Text(if (model.partialBytes > 0) "Continue the download" else "Download ${sizeMb(model.bytes)}")
                }
            }
            is ModelState.Downloading -> {
                Text("Downloading ${model.model.label}… ${model.percent}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { model.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is ModelState.DownloadFailed -> {
                Text(model.reason, color = MaterialTheme.colorScheme.error)
                Button(onClick = { haptics.tap(); vm.transcripts.downloadModel() }) { Text("Try again") }
            }
            is ModelState.Ready -> {
                Text(
                    ui.estimateMs?.let { "About ${spoken(it)} on this phone, with ${model.label}." }
                        ?: "With ${model.label}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { haptics.tap(); vm.transcripts.transcribe() }) { Text("Transcribe") }
            }
        }
    }
}

/** One line: when, who (when the sides were apart), what. */
@Composable
private fun Line(line: TranscriptSegment, named: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            lineTime(line.startMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            if (named && line.speaker != Speaker.NONE) {
                Text(
                    if (line.speaker == Speaker.YOU) "You" else "Them",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = speakerColour(line.speaker),
                )
            }
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The look's own two accents: its primary for the phone's owner, its tertiary for the other side. */
@Composable
private fun speakerColour(speaker: Speaker): Color =
    if (speaker == Speaker.YOU) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

/** How it was made: the model, the language, and what it cost. */
@Composable
private fun Facts(transcript: Transcript, playing: Boolean) {
    val f = transcript.facts
    val language = if (f.language == "auto") "language detected" else Locale.forLanguageTag(f.language).getDisplayLanguage(Locale.ENGLISH)
    Text(
        "${f.modelId}, $language. Took ${spoken(f.wallMs)} for ${spoken(f.audioMs)} of call." +
            if (playing) "" else " Tap a line to hear it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** 1:23, or 1:02:03 past an hour. */
internal fun lineTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

/** "40 seconds", "6 minutes", "1 h 10 min". */
internal fun spoken(ms: Long): String {
    val s = (ms + 500) / 1000
    return when {
        s < 60 -> "$s seconds"
        s < 3600 -> ((s + 30) / 60).let { if (it == 1L) "1 minute" else "$it minutes" }
        else -> "${s / 3600} h ${s / 60 % 60} min"
    }
}

internal fun sizeMb(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
