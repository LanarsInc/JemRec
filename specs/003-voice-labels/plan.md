# Implementation Plan: Who spoke in a one-mix call

**Branch**: `feature/implement-voice-recognation` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-voice-labels/spec.md`

## Summary

When a call's two channels carry one mix (`ChannelSides` says MIX), the
lines are saved unnamed as today, then a voice step tells the two voices
apart on the phone with sherpa-onnx (pyannote segmentation-3.0 and
TitaNet-small, the pair R16 measured on the A24), chunk by chunk, links the
chunks, labels each line and cuts a line at a turn by its word times. A print
of the owner's voice - from a 30 s recording, else from the left side of
their messenger calls - turns the voice that matches it clearly into You and
the other into Them; otherwise Voice 1 / Voice 2, and the user can mark which
is theirs. The labels reach the summary and the Sales Engine (Seller,
Customer, Speaker 1/2). Calls with two channels are untouched.

## Technical Context

**Language/Version**: Kotlin (AGP 9.4 built-in Kotlin), C++17 for the JNI
bridge; JDK 21 to build

**Primary Dependencies**: new: sherpa-onnx v2.0.1 (Apache-2.0) as a submodule,
C API only; onnxruntime's prebuilt arm64 `libonnxruntime.so` (MIT). Models:
pyannote segmentation-3.0 int8 (MIT), TitaNet-small (licence to check, V2;
CAM++ Apache-2.0 as the fallback). Existing: whisper.cpp, llama.cpp, Room
2.8.4, WorkManager, Compose Material 3

**Storage**: Room `transcripts` gets `voiceSource`, `voiceModel` (version 2);
`transcript_segments.speaker` takes `voice1`, `voice2`; the owner's print in
`noBackupFilesDir/voice/print.bin`

**Testing**: JUnit + Robolectric for the plain-Kotlin decisions; Roborazzi
screens; an instrumented accuracy test run with `adb shell am instrument`;
the A24 with the user for real calls; one emulator check

**Target Platform**: Android 12+ (minSdk 31), arm64-v8a only

**Project Type**: mobile app; `shellserver/` untouched

**Performance Goals**: SC-003 (at most half the call's length on the A24;
R16 0.30-0.40), SC-004 (peak under 500 MB; R16 349 MB)

**Constraints**: nothing leaves the phone; the transcript is never later than
without the voice step; never beside a summary; wrong names worse than none;
calls with two channels byte-for-byte as before (SC-006)

**Scale/Scope**: calls up to hours long in 5-min chunks; one print per phone;
about 15-19 MB more APK, 42 MB of models to download

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; the gates are
CLAUDE.md's rules.

| Gate | Status |
|---|---|
| JDK 21; the daemon's bytes unchanged | Pass: `shellserver/` untouched |
| Native engines from pinned submodules, NDK 28.2, CMake 3.22.1, arm64 | Pass with one check: sherpa-onnx is a submodule built by our CMake; onnxruntime is its prebuilt library, its libc++ checked first (V1), built from source if it does not fit |
| Models downloaded, never committed | Pass: V2, with sha256 |
| Native tests with `am instrument`, never connected tasks | Pass: quickstart |
| Decisions in plain code, tested off the phone | Pass: `VoiceLink`, `VoiceSeam`, `VoiceNames`, `OwnerPrintFromCalls`, the migration |
| Screen tests, PNGs looked at | Pass: Voices group, Record your voice, transcript with voices |
| `./gradlew lint` no warnings | Required: `RECORD_AUDIO` is new and must lint clean |
| Generated files from their source | Pass: site via `tools/build_site.py` |
| Feature landing: every place says the same | Planned: last phase - README (feature table, FAQ, privacy and permission lines, test counts), `build_site.py` (features, manual: Settings, the transcript, privacy: the voice print as new data kept, the microphone), ARCHITECTURE (a section, the permission table), DEVELOPMENT and tools/README, fastlane description and a changelog with the bump, screenshots and demo data, an emulator check |
| Never commit call audio | Pass: accuracy fixtures are mixed on the fly and deleted; the emulator fixture is synthetic |
| Comments say why, with the measurement | Pass: thresholds V5-V7 carry R16's numbers and are re-measured before release |
| Replacing a code path lists what it did | `Speaker` gains two values: every `when` over it (TranscriptScreen, SummaryRunner, ContentText, QuietSide, LineCleanup, copy/share) is listed in the task and shown to keep its old behaviour for `you`/`them`/`none` |
| Ask before creating or deleting an AVD | Open: only if the emulator check needs a new one |
| Commit, push or PR only when asked | Commits per story (the user asked, 2026-10-29); no push or PR |

Re-checked after design: unchanged. One new permission (`RECORD_AUDIO`,
asked only on Record) is justified by the decided sample (V7) and goes into
every permission list.

## Project Structure

### Documentation (this feature)

```text
specs/003-voice-labels/
├── spec.md
├── plan.md
├── research.md          # V1-V11
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── ui.md
│   └── voice-native.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
third_party/sherpa-onnx/                     # new submodule, v2.0.1
app/src/main/cpp/
├── CMakeLists.txt                           # + sherpa-onnx C API, voicejr
└── voicejr.cpp                              # new JNI bridge
app/src/main/java/com/jemcik/jemrec/
├── transcribe/
│   ├── TranscriptSegment.kt                 # Speaker: + VOICE1, VOICE2
│   ├── TranscriptionRunner.kt               # voice step after MIX lines
│   ├── VoiceNative.kt                       # new
│   ├── VoiceStep.kt                         # new: chunks, embed, name, save
│   ├── VoiceLink.kt                         # new, plain: chunks into a call
│   ├── VoiceSeam.kt                         # new, plain: label and cut lines
│   ├── VoiceNames.kt                        # new, plain: print -> You/Them
│   ├── OwnerPrint.kt                        # new: file, sample, from calls
│   ├── OwnerPrintFromCalls.kt               # new, plain: V7's tests
│   └── model/VoiceModel.kt                  # new: two files as one
├── transcripts/db/                          # TranscriptEntry + migration 1->2
├── summary/SummaryRunner.kt                 # Speaker 1/2
├── crm/ContentText.kt                       # Speaker 1/2
└── ui/
    ├── TranscriptScreen.kt                  # labels, "Which one is you?"
    ├── TranscriptsSettingsScreen.kt         # Voices group
    └── RecordVoiceScreen.kt                 # new
app/src/main/AndroidManifest.xml             # RECORD_AUDIO
app/src/test/.../transcribe/Voice*Test.kt    # new
app/src/androidTest/.../VoiceAccuracyTest.kt # new, am instrument
```

**Structure Decision**: one app module, as before; the voice code sits in
`transcribe/` beside whisper's, since it runs inside the transcription and
shares its chunking, lock and model download.

## Phases (for /speckit-tasks)

1. **Engine in the APK** (V1): the libc++ check, the submodule, CMake,
   `voicejr.cpp`, `VoiceNative`; a smoke test under `am instrument` on a
   synthetic two-voice clip. Gate: R16's speed reproduced in the app.
2. **US1** (P1): `VoiceModel` download, `VoiceStep`, `VoiceLink`,
   `VoiceSeam`, `Speaker.VOICE1/2`, migration, transcript labels, settings
   row; the accuracy test (SC-001).
3. **US2** (P1): `OwnerPrint` (sample, from calls), `VoiceNames`, Record
   screen and permission, "Which one is you?", mark and undo; the user's
   calls (SC-002).
4. **US3** (P2): summary and CRM labels.
5. **US4** (P3): transcribing again applies the voice step; nothing redone
   by itself.
6. **Every place that describes it** (CLAUDE.md's list) and the emulator
   check; the version bump with its changelog.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| A prebuilt native library (onnxruntime) | sherpa-onnx's models run on it; building it from source is long and not needed for an APK-only fork | Writing the models' inference in ggml: no maintained port exists; it is a project of its own |
| A new permission (`RECORD_AUDIO`) | the recorded sample is the cleanest print (decided 2026-11-03) | Recording through the shell daemon: works only while it runs and puts a non-call capture in the call recorder |
