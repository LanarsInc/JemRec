package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jemcik.jemrec.transcribe.LanguageNames
import com.jemcik.jemrec.transcribe.Sides
import com.jemcik.jemrec.transcribe.TranscribeMode
import com.jemcik.jemrec.transcribe.TranscriptionScheduler
import com.jemcik.jemrec.transcribe.TranscriptionLanguageChoice

/**
 * Settings > Transcripts and summaries: the language calls are transcribed
 * in, the speech models on the phone, and who said which line.
 *
 * Built from the rows the rest of Settings is (SettingsGroup, SettingsRow,
 * SwitchRow, ChoiceRow, ChoiceDialog), so it looks as they do in each look.
 */
@Composable
fun TranscriptsSettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        vm.transcripts.openSettings()
        onDispose { vm.transcripts.closeSettings() }
    }
    val ui by vm.transcripts.settingsState.collectAsStateWithLifecycle()
    TranscriptsSettingsContent(ui, vm, modifier)
}

@Composable
internal fun TranscriptsSettingsContent(ui: TranscriptsSettingsUi, vm: MainViewModel, modifier: Modifier = Modifier) {
    var choosingLanguage by remember { mutableStateOf(false) }
    val c = vm.transcripts
    Column(modifier) {
        SettingsGroup("Language") {
            row {
                SettingsRow(
                    title = "Calls are in",
                    summary = LanguageNames.both(ui.language),
                    onClick = { choosingLanguage = true },
                )
            }
            row {
                SwitchRow(
                    title = "Use a model made for the language",
                    summary = "NB-Whisper for Norwegian, when it is downloaded",
                    checked = ui.languageModels,
                    onCheckedChange = c::setLanguageModels,
                )
            }
        }

        // One group to TalkBack, so the three are heard as one choice.
        Column(Modifier.selectableGroup()) {
            SettingsGroup("When") {
                TranscribeMode.entries.forEach { mode ->
                    row(mode) {
                        ChoiceRow(
                            title = modeTitle(mode),
                            selected = mode == ui.mode,
                            onSelect = { c.setMode(mode) },
                            lines = listOf("" to modeLine(mode)),
                        )
                    }
                }
            }
        }
        SettingsGroup {
            if (ui.mode == TranscribeMode.OVERNIGHT) {
                row {
                    SwitchRow(
                        title = "Only while charging",
                        summary = "A night's backlog is hours of work for the phone",
                        checked = ui.chargingOnly,
                        onCheckedChange = c::setChargingOnly,
                    )
                }
            }
            row {
                SwitchRow(
                    title = "Summarise after each transcript",
                    summary = if (ui.summaryModels.none { it.installed && it.fits }) "Needs a summary model, below" else "With the summary model below",
                    checked = ui.summariseAfter,
                    onCheckedChange = c::setSummariseAfter,
                )
            }
        }

        SettingsGroup(
            "Speech models",
            note = "Downloaded once; transcripts are made on the phone and the call never leaves it.",
        ) {
            ui.models.forEach { m -> row(m.model) { ModelRowView(m, chosen = m.model == ui.chosenModel, vm = vm) } }
            row {
                SwitchRow(
                    title = "Download over Wi-Fi only",
                    summary = "Models are hundreds of megabytes",
                    checked = ui.wifiOnly,
                    onCheckedChange = c::setWifiOnly,
                )
            }
        }

        SettingsGroup(
            "Summary models",
            note = "A summary is written from the transcript by a language model on the phone. " +
                "Only models this phone has the memory for can be used.",
        ) {
            ui.summaryModels.forEach { m -> row(m.model) { SummaryModelRowView(m, chosen = m.model == ui.summaryModel, vm = vm) } }
        }

        // One group to TalkBack, so the three are heard as one choice.
        Column(Modifier.selectableGroup()) {
            SettingsGroup("Who spoke") {
                Sides.entries.forEach { sides ->
                    row(sides) {
                        ChoiceRow(
                            title = sidesTitle(sides),
                            selected = sides == ui.sides,
                            onSelect = { c.setSides(sides) },
                            lines = listOf("" to sidesLine(sides)),
                        )
                    }
                }
            }
        }

        if (ui.imported.isNotEmpty()) {
            SettingsGroup("Imported models (debug build)") {
                ui.imported.forEach { name -> row(name) { SettingsRow(title = name, summary = "Put on the phone by hand") } }
            }
        }

        SettingsFooter(
            "A call whose two sides are on the two channels - every messenger call, and phone calls on " +
                "phones that keep them apart - has each line named You or Them. Where both channels " +
                "carry the same sound, the lines are not named.",
        )
    }
    if (choosingLanguage) {
        ChoiceDialog(
            title = "Calls are in",
            choices = listOf(TranscriptionLanguageChoice.AUTO) + LanguageNames.offered,
            selected = ui.language,
            label = LanguageNames::own,
            detail = { LanguageNames.english(it) ?: "Slower, and sometimes wrong: say it when you can" },
            onChoose = c::setSettingsLanguage,
            onDismiss = { choosingLanguage = false },
        )
    }
}

/** A model: its name and size, whether it is here, and what can be done with it. */
@Composable
private fun ModelRowView(row: ModelRow, chosen: Boolean, vm: MainViewModel) {
    val c = vm.transcripts
    val m = row.model
    val marks = listOfNotNull(
        "Made for Norwegian".takeIf { m.isLanguageSpecific },
        "Recommended for this phone".takeIf { row.recommended },
        "In use".takeIf { chosen && row.installed && !m.isLanguageSpecific },
    )
    val state = when {
        row.downloading != null -> "Downloading… ${row.downloading}%"
        row.installed -> "Downloaded"
        row.partialBytes > 0 -> "${sizeMb(row.partialBytes)} of ${sizeMb(m.sizeBytes)} downloaded"
        else -> sizeMb(m.sizeBytes)
    }
    SettingsRow(
        title = m.label,
        summary = (listOf(state) + marks).joinToString(" · "),
        // A tap picks a general model to use; the Norwegian ones are picked by the language.
        onClick = if (row.installed && !m.isLanguageSpecific && !chosen) ({ c.chooseModel(m) }) else null,
        trailing = {
            when {
                row.downloading != null -> TextButton(onClick = { c.cancelDownload(m) }) { Text("Stop") }
                row.installed -> TextButton(onClick = { c.delete(m) }) { Text("Delete") }
                else -> TextButton(onClick = { c.download(m) }) { Text(if (row.partialBytes > 0) "Continue" else "Download") }
            }
        },
    )
}

/** A summary model: its size and memory, whether the phone can hold it, and what can be done. */
@Composable
private fun SummaryModelRowView(row: SummaryModelRow, chosen: Boolean, vm: MainViewModel) {
    val c = vm.transcripts
    val m = row.model
    val state = when {
        !row.fits -> "Needs a phone with ${m.needsGb} GB"
        row.downloading != null -> "Downloading… ${row.downloading}%"
        row.installed -> "Downloaded"
        row.partialBytes > 0 -> "${sizeMb(row.partialBytes)} of ${sizeMb(m.sizeBytes)} downloaded"
        else -> "${sizeMb(m.sizeBytes)}, ${sizeMb(m.peakMemoryBytes)} of memory"
    }
    SettingsRow(
        title = m.label,
        summary = listOfNotNull(state, "In use".takeIf { chosen && row.installed }).joinToString(" · "),
        enabled = row.fits,
        onClick = if (row.fits && row.installed && !chosen) ({ c.chooseSummaryModel(m) }) else null,
        trailing = {
            if (row.fits) {
                when {
                    row.downloading != null -> TextButton(onClick = { c.cancelDownload(m) }) { Text("Stop") }
                    row.installed -> TextButton(onClick = { c.delete(m) }) { Text("Delete") }
                    else -> TextButton(onClick = { c.download(m) }) { Text(if (row.partialBytes > 0) "Continue" else "Download") }
                }
            }
        },
    )
}

private fun modeTitle(mode: TranscribeMode) = when (mode) {
    TranscribeMode.MANUAL -> "Only when I ask"
    TranscribeMode.AFTER_CALL -> "After each call"
    TranscribeMode.OVERNIGHT -> "Overnight"
}

private fun modeLine(mode: TranscribeMode) = when (mode) {
    TranscribeMode.MANUAL -> "From the Transcript button on a recording."
    TranscribeMode.AFTER_CALL -> "As soon as a call is saved. The phone works for about as long as the call."
    TranscribeMode.OVERNIGHT -> "Every call of the last ${TranscriptionScheduler.OVERNIGHT_DAYS} days not yet transcribed, at night."
}

private fun sidesTitle(sides: Sides) = when (sides) {
    Sides.AUTO -> "Automatic"
    Sides.SPLIT -> "Always by channel"
    Sides.MIX -> "Never"
}

private fun sidesLine(sides: Sides) = when (sides) {
    Sides.AUTO -> "Named when the two channels carry the two sides; checked for each call."
    Sides.SPLIT -> "Left is You, right is Them, whatever the channels carry."
    Sides.MIX -> "One text for the whole call, no names."
}
