# Quickstart: checking who spoke in a one-mix call

What proves the feature, in the order it is built. Real calls need the
user; say what was measured and what was inferred.

## Off the phone

```bash
./gradlew testDebugUnitTest --tests '*.transcribe.Voice*'
```

`VoiceLink`, `VoiceSeam`, `VoiceNames`, `OwnerPrintFromCalls` and the
migration: plain Kotlin with fixed embeddings and turns, no models.

```bash
./gradlew testDebugUnitTest --tests '*.ui.screens.*' -PrecordScreens=true
```

New screens: the Voices group in its states, Record your voice, a transcript
with Voice 1 / Voice 2 and "Which one is you?". Look at every PNG that
changed.

## Accuracy against a truth (SC-001, V4, V5)

Telegram calls from the A24 or the Honor, both channels mixed to one, run
through the voice step by the instrumented test (`adb shell am instrument`,
never Gradle's connected task); the truth is the frames where one channel
leads by 10 dB (as in R16). Expected: at least 95% of those frames under the
right voice; a 20-30 min call linked across chunks as well as a short one.
Copies deleted after; no audio committed.

## The owner's voice (SC-002, V6, V7)

On the A24, with the user:

1. Record your voice in Settings; transcribe ten of the user's one-mix phone
   calls: You on the user's lines in at least eight, never the wrong way.
2. Five calls between other people (two synthetic voices, a voice menu
   alone): Voice 1 / Voice 2, or unnamed for one voice.
3. Delete the print; with "learn from my calls" on, make three Telegram
   calls; the print appears "from 3 calls"; repeat step 1's calls.
4. R16's test calls whose left side carried played audio or echo: none of
   them makes a print.

## Speed and memory (SC-003, SC-004)

The app logs the voice step's time and peak memory to its own log, read
with `run-as`. On the A24: at most half the call's
length on top of the transcription; peak under 500 MB; never during a
summary.

## Calls with two channels (SC-006)

A messenger call transcribed before and after the feature: the same lines,
labels and times (the voice step does not run: its log line says "sides
SPLIT, voices skipped").

## Emulator

`tools/emulator_checks.py` gains a check that a one-mix recording with the
voice model present gets Voice 1 / Voice 2 lines (a fixture of two synthetic
voices, made by the script, never a real call).
