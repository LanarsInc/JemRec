# Implementation Plan: Transcripts and summaries of calls

**Branch**: `feature/add-transcribe-and-summarize` | **Date**: 2026-10-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-transcripts-summaries/spec.md`

## Summary

JemRec transcribes and summarises calls on the phone, in 17 languages
(CallVault's 16 plus Norwegian), and finds words in them. The engines and most
of the pipeline are taken from CallVault (whisper.cpp, llama.cpp, their JNI
bridges, resampler, chunking, queues, workers, model download, summary prompt
and grammar, Room schema with full-text search). JemRec's own parts: who spoke
comes from its stereo channels; its look, through one Transcript screen; and a
model choice sized for the test phone, a 6 GB Galaxy A24, where CallVault's
summariser does not fit.

## Technical Context

**Language/Version**: Kotlin (AGP 9.4 built-in Kotlin, compose plugin 2.3.21), C++17 for the JNI bridges; JDK 21 to build

**Primary Dependencies**: whisper.cpp `371b5a75` and llama.cpp `a298422da7` (submodules, MIT); Silero VAD v5.1.2 ggml (MIT, asset); Room 2.8.4 + KSP 2.3.9; WorkManager 2.9.1; Compose Material 3 (BOM 2026.08.00); NDK 28.2.13676358; CMake 3.22.1

**Storage**: Room database `transcripts.db` (transcripts, segments, summaries, FTS4); models in `noBackupFilesDir/models/`; settings in `Prefs`

**Testing**: JUnit + Robolectric unit and screen tests (Roborazzi); instrumented tests for the JNI bridges and Room migrations; `tools/emulator_checks.py`; measurements on the A24

**Target Platform**: Android 12+ (minSdk 31), arm64-v8a

**Project Type**: mobile app (app module; `shellserver/` daemon untouched)

**Performance Goals**: SC-001 (5 min call in under 10 min on the A24), SC-004 (summary under 5 min)

**Constraints**: transcription peak under 1.5 GB; summary peak under 2.5 GB on 6 GB phones; offline except model downloads; recorder unaffected

**Scale/Scope**: hundreds of calls per phone; models 175 MB to 2.6 GB; 17 languages

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; the gates are
CLAUDE.md's rules.

| Gate | Status |
|---|---|
| JDK 21; the daemon's bytes unchanged | Pass: `shellserver/` untouched |
| Decisions in plain code, tested off the phone | Pass: queues, policies, channel decision, resampler, chunking, prompt, language resolution, grammar parse, citations, search text are plain Kotlin with unit tests (CallVault's tests come along) |
| Screen tests, PNGs looked at | Pass: Transcript screen (states x looks), Summary, Settings section |
| `./gradlew lint` no warnings | Required; JNI, Room and UI must lint clean |
| Generated files from their source | Pass: site via `tools/build_site.py`, screenshots via `tools/shoot.py` |
| Feature landing: every place says the same | Planned: phase 9 |
| Never commit call audio | Pass: FLEURS clips and test calls stay outside the repo |
| Comments say why, with the measurement | Pass: A24 figures go in as measured |
| Replacing a code path lists what it did | n/a: nothing is replaced. The "no native code" sentence in ARCHITECTURE ("Playing a recording") becomes false and is corrected |
| Ask before creating or deleting an AVD | Open: the named AVDs are not on this machine (research R12) |

Re-checked after design: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/001-transcripts-summaries/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── native-bridges.md
│   └── ui.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
.gitmodules                                   # whisper.cpp, llama.cpp
third_party/whisper.cpp/  third_party/llama.cpp/
app/build.gradle.kts                          # ndkVersion, abiFilters, externalNativeBuild, ksp, room
app/schemas/                                  # Room schema exports
app/src/main/cpp/
├── CMakeLists.txt
├── whisperjr.cpp                             # from CallVault whispercv.cpp
└── llamajr.cpp                               # from CallVault llamacv.cpp
app/src/main/assets/ggml-silero-v5.1.2.bin
app/src/main/AndroidManifest.xml              # WorkManager's foreground service as specialUse
app/src/main/java/com/jemcik/jemrec/
├── transcribe/                               # from CallVault transcription/, trimmed
│   ├── WhisperNative.kt  TranscriptionEngine.kt  TranscriptionRunner.kt
│   ├── AudioIn.kt                            # PcmDecoder -> 16 kHz per channel (CallVault resampler)
│   ├── ChannelSides.kt                       # split or downmix, per recording (R4)
│   ├── ChunkPlan.kt  SpeechGapSnap.kt  WrongScriptRetry.kt  LanguageScript.kt
│   ├── TranscriptionLanguage.kt              # 17 + auto, override, default
│   ├── TranscriptionQueue.kt  TranscriptionWorker.kt  TranscriptionScheduler.kt
│   ├── HeavyWorkNotification.kt  TranscriptionProgress.kt  TranscriptionEstimate.kt
│   ├── VadModel.kt
│   └── model/                                # SpeechModel, ModelRepository, ModelDownloadWorker, ModelDownloadPolicy
├── summary/                                  # from CallVault summary/
│   ├── LlamaNative.kt  SummaryEngine.kt  SummaryRunner.kt  SummaryWorker.kt  SummaryQueue.kt  SummaryScheduler.kt
│   ├── SummaryModel.kt                       # three candidates, offered by memory (R6)
│   ├── CallSummary.kt  SummaryPrompt.kt  SummaryGrammar.kt  SummaryChunking.kt
│   └── SummaryCitations.kt  SummaryDedupe.kt  SummaryLanguage.kt  SummaryText.kt
├── transcripts/                              # from CallVault data/transcripts/, trimmed
│   ├── db/                                   # TranscriptDatabase, entries, DAOs, FTS
│   ├── TranscriptRepository.kt  TranscriptSearch.kt
│   └── export/                               # TXT, Markdown, SRT, VTT, JSON
├── capture/RecordingSaver.kt                 # after-call hook
├── capture/Trash.kt                          # delete derived text for good
└── ui/
    ├── TranscriptScreen.kt                   # new: Transcript | Summary
    ├── TranscriptsSettingsScreen.kt          # new
    ├── RecordingRows.kt                      # icon in RowActions, transcribed mark
    ├── HomeScreen.kt  ListChips.kt           # search hits, Transcribed chip
    ├── MainScreen.kt  MainViewModel.kt  SettingsList.kt
app/src/test/java/com/jemcik/jemrec/{transcribe,summary,transcripts}/
app/src/test/java/com/jemcik/jemrec/ui/screens/
app/src/androidTest/                          # JNI bridges, Room migration, faithfulness samples
app/src/debug/.../DemoRecordings.kt           # demo transcripts and summaries
README.md  docs/ARCHITECTURE.md  docs/DEVELOPMENT.md  tools/README.md
tools/build_site.py  tools/shoot.py  tools/emulator_checks.py
fastlane/metadata/android/en-US/              # descriptions, changelog
gradle.properties                             # 0.26.0
```

**Structure Decision**: three new packages beside `capture` and `ui`
(`transcribe`, `summary`, `transcripts`), mirroring CallVault so its code
moves with renames rather than rewrites; UI stays JemRec's.

## Phases (for /speckit-tasks)

1. **Measure the phone first** - on the A24: are a phone call's two channels
   different (R4)? Answer decides whether labels come from channels there.
2. **Native build** - submodules, CMake, both bridges renamed, Gradle; both
   `systemInfo` lines on the A24 show DOTPROD; APK size noted.
3. **Models** - catalogues, download, verification, Wi-Fi policy, debug import,
   VAD asset, memory gate for summary models.
4. **Transcription pipeline** - audio in, channel decision, chunking, runner,
   wrong-script retry, language (17 + override), storage. Unit tests.
5. **Storage and search** - Room, FTS, repository, export. Migration test.
6. **Work** - queues, workers, engine lock, notification, three modes,
   after-call hook, cancel.
7. **Summaries** - engine, runner, prompt with Norwegian, grammar, chunking,
   citations, worker, auto after transcript; faithfulness samples incl.
   Norwegian and Ukrainian.
8. **Interface** - Transcript screen (states, lines, summary, citations,
   copy, export, language override), row icon and mark, search hits, chip,
   Settings; screen tests recorded and every PNG looked at.
9. **Every place that describes it** - README (table, FAQ, privacy and
   permission lines, test counts), `build_site.py` (features, FAQ, manual,
   privacy and chips) then run it, ARCHITECTURE (section, contents, permission
   table, "no native code"), DEVELOPMENT and tools/README, fastlane and
   changelog with the 0.26.0 bump, demo data, `SCREENS` / `LISTING_ORDER`,
   `tools/shoot.py`, an emulator check.
10. **Measure and set defaults** - quickstart runs on the A24; R14 filled;
    speech and summary defaults and thread count set from it.
11. **Gates** - unit, screen, lint, instrumented, emulator checks.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Native code (NDK, CMake, two submodules) in an app that had none | No JVM engine comes near Whisper or a local LLM | Android's recogniser takes no files; cloud sends the call away |
| Internet use beyond 127.0.0.1 | Models are 175 MB to 2.6 GB | Bundling makes a multi-GB APK |
| Room and KSP in an app that keeps its data in files | Full-text search across transcripts and summaries | Scanning JSON files per keystroke does not scale and has no ranking |
| A summariser other than CallVault's measured one | Gemma 4 E2B's 3.5 GB peak on a 5.8 GB phone | Offering it on the A24 invites background kills; it stays for 8 GB phones |
