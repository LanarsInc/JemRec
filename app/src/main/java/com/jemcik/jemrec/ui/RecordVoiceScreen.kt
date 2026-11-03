package com.jemcik.jemrec.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jemcik.jemrec.transcribe.VoiceSample

/**
 * Your voice: the owner reads a short text once, and phone calls that come
 * out as one mix can then say You and Them (specs/003-voice-labels, US2,
 * contracts/ui.md). The microphone is asked for at Start, with why; the sound
 * is kept in memory only, and what is kept is numbers describing the voice.
 */
@Composable
fun RecordVoiceScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val sample by vm.transcripts.sample.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val microphone = rememberPermissionRequest { result ->
        if (result.values.all { it }) vm.transcripts.startSample()
    }
    RecordVoiceContent(
        sample = sample,
        haptics = haptics,
        onStart = { microphone.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
        onStop = vm.transcripts::stopSample,
        onDone = { vm.back() },
        modifier = modifier,
    )
}

/** The text read aloud: plain everyday words, about half a minute at a speaking pace. */
internal const val SAMPLE_TEXT =
    "Hello, this is my own voice, recorded once so the app can tell me apart from the person I talk to. " +
        "I usually call about orders, deliveries and meetings. We agree on a day, a time and a place, " +
        "and I write down what we said. If something changes, I call back and we find another day. " +
        "Thank you for your time, and have a good afternoon."

@Composable
internal fun RecordVoiceContent(
    sample: SampleUi,
    haptics: HapticFeedback,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // No scroll of its own: Settings' screens sit in MainScreen's, and a second one inside it crashes.
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Read this aloud at your usual pace, as if on a call. It takes about half a minute.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(SAMPLE_TEXT, style = MaterialTheme.typography.headlineSmall)

        when (sample) {
            is SampleUi.Recording -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recording… ${sample.elapsedMs / 1000} of ${VoiceSample.LENGTH_MS / 1000} s", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (sample.elapsedMs.toFloat() / VoiceSample.LENGTH_MS).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                // The level from -60 dBFS (nothing) to 0: so the owner sees the phone hears them.
                Text("Level", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { ((sample.levelDb + 60) / 60).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { haptics.tap(); onStop() }) { Text("Stop") }
            }
            SampleUi.Describing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Learning your voice…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SampleUi.Saved -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Done. Phone calls transcribed from now on say You for your lines when your voice is clear in them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { haptics.tap(); onDone() }) { Text("Done") }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (sample) {
                    is SampleUi.TooShort -> Text(
                        "Too short - only ${sample.speechMs / 1000} s of speech. Read it again, a little louder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SampleUi.NoMicrophone -> Text(
                        "JemRec needs the microphone for this, once. Allow it when asked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SampleUi.Failed -> Text(
                        "Your voice could not be learnt. Try again; the voice model may need downloading again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { haptics.tap(); onStart() }) { Text(if (sample == SampleUi.Idle) "Start" else "Start again") }
                }
            }
        }

        Text(
            "Kept as numbers that describe your voice, not as a recording. It never leaves the phone, " +
                "and Delete in Settings removes it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
