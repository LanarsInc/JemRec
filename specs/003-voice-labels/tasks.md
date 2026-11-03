# Tasks: Who spoke in a one-mix call

**Input**: Design documents from `specs/003-voice-labels/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. CLAUDE.md requires decisions to be tested off the phone
and screen tests for new screens.

## Conventions for every task

- `JR/` means `app/src/main/java/com/jemcik/jemrec/`; `JRT/` means
  `app/src/test/java/com/jemcik/jemrec/`; `JRA/` means
  `app/src/androidTest/java/com/jemcik/jemrec/`; `Vn` is a section of
  [research.md](research.md).
- `VoiceLink`, `VoiceSeam`, `VoiceNames`, `VoiceSilence` and
  `OwnerPrintFromCalls` have no Android types and are unit tested. Comments
  say why, with R16's measurement behind each threshold.
- Native tests run with `adb shell am instrument`, never Gradle's connected
  task (it uninstalls the app). Status line before any device step. Device:
  Galaxy A24 running `com.lanars.jemrec`. Never commit call audio; mixed
  copies are deleted after.
- Never log a transcript line, a contact or the print's numbers.
- Commit after each phase (the user asked for commits per story); no push.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: US1..US4 of the spec

---

## Phase 1: Setup (the engine in the APK)

**Purpose**: sherpa-onnx built and callable from Kotlin (V1, contracts/voice-native.md)

- [X] T001 Fetch sherpa-onnx v2.0.1's onnxruntime arm64 library into the scratchpad, run `readelf -d` on `libonnxruntime.so`, and record what it NEEDS (libc++_shared or not, which version) under V1 in specs/003-voice-labels/research.md; if it needs a libc++ newer than NDK 28.2's, stop and ask the user before building onnxruntime from source
- [X] T002 Add sherpa-onnx as a submodule pinned to v2.0.1 at third_party/sherpa-onnx (.gitmodules), and note it beside whisper.cpp and llama.cpp in docs/DEVELOPMENT.md
- [X] T003 Build sherpa-onnx's C API only (ASR, TTS, KWS, websocket, binaries off) with onnxruntime's prebuilt library in app/src/main/cpp/CMakeLists.txt; package `libonnxruntime.so` (and libc++_shared from NDK 28.2 if T001 says so) for arm64-v8a, keeping the app's own code on c++_static
- [X] T004 Write the JNI bridge app/src/main/cpp/voicejr.cpp: `open`, `turns` (two voices, clustering by count, triples startMs/endMs/voice), `embed` (L2-normalised), `close`, as contracts/voice-native.md; failures return empty arrays and log why
- [X] T005 Write JR/transcribe/VoiceNative.kt with the externals and their doc comments (threads from `TranscriptionEngine.preferredThreadCount()`)
- [X] T006 Write the smoke test JRA/transcribe/VoiceBridgeTest.kt beside WhisperBridgeTest, in its manner: the library loads; a missing model returns 0; with the models on the phone (`assumeTrue` otherwise, as WhisperBridgeTest does for whisper small), a Telegram recording already on the phone mixed to one in memory gives two voices and a 192-float L2-normalised embedding. Run with `am instrument` on the A24 and record time and peak memory against R16 (RTF 0.30-0.40, 349 MB) in V4
- [X] T007 Measure the APK size before and after T003 and record it in V11 of specs/003-voice-labels/research.md

**Checkpoint**: R16's speed reproduced inside the app, or the gap explained in V1/V4.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the model download, the new labels, and the database, which every story needs

- [X] T008 Check TitaNet-small's licence (V2); write the result in V2 and, if it is CC-BY-4.0, add its attribution to NOTICE.md; if it cannot ship, switch to CAM++ and measure its speed on the A24 first
- [X] T009 [P] Write JR/transcribe/model/VoiceModel.kt: the segmentation and embedding files as two `DownloadableModel`s presented as one (id, summed size, `peakMemoryBytes` from T006, the twice-the-peak memory rule as summary models), with URLs and sha256 from sherpa-onnx's release assets
- [X] T010 Teach JR/transcribe/model/ModelRepository.kt and ModelDownloadWorker.kt to download, verify, unpack (if the asset is an archive) and delete the voice model's two files as one item. Done in VoiceModel itself: the worker already takes any DownloadableModel, both files are plain on Hugging Face (nothing to unpack), and imports list only .bin/.gguf, so neither file needed a change
- [X] T011 [P] Add `VOICE1("voice1")` and `VOICE2("voice2")` to `Speaker` in JR/transcribe/TranscriptSegment.kt, and list in its doc comment every `when` over it and what each does with the new values
- [X] T012 Go through every `when (speaker)` (JR/ui/TranscriptScreen.kt, JR/summary/SummaryRunner.kt, JR/crm/ContentText.kt, JR/transcribe/QuietSide.kt, JR/transcribe/LineCleanup.kt, copy and share) so `you`/`them`/`none` behave exactly as before and Voice 1/2 pass through QuietSide and LineCleanup untouched; extend their existing tests in JRT/ with a Voice 1/2 case each
- [X] T013 Add `voiceSource` (default `none`) and `voiceModel` to JR/transcripts/db/TranscriptEntry.kt, bump JR/transcripts/db/TranscriptDatabase.kt to version 2 with the migration of data-model.md (`channels` where a `you`/`them` line exists), and add the DAO update that rewrites one transcript's line labels and `voiceSource` in one transaction in JR/transcripts/db/TranscriptDao.kt
- [X] T014 [P] Migration test in JRT/transcripts/db/TranscriptMigrationTest.kt (version 1 built from its exported schema, opened by Room at version 2: MigrationTestHelper needs the schemas as APK assets, which JVM tests lack): a version-1 database with a split, a mixed and an empty transcript comes out `channels`, `none`, `none`

**Checkpoint**: the app builds, lint passes, every existing test passes unchanged.

---

## Phase 3: User Story 1 - Two voices apart in a one-mix call (Priority: P1) 🎯 MVP

**Goal**: one-mix transcripts get Voice 1 / Voice 2, lines cut at a turn; split calls untouched

**Independent Test**: on the A24, a two-person phone call transcribed with the voice model downloaded shows two voices whose turns match what was said (spec US1)

### Tests for User Story 1

- [X] T015 [P] [US1] JRT/transcribe/VoiceLinkTest.kt: chunks with fixed centroids and overlapping turns link into two call voices; a chunk with one voice joins the nearer; a tie settled by the overlap (V4)
- [X] T016 [P] [US1] JRT/transcribe/VoiceSeamTest.kt: a line takes the voice covering most of it; a line with at least 1 s of the other voice is cut at a word boundary within 300 ms of the turn, words kept once and in order; no boundary near, no cut; word times mapped from the VAD timeline as SpeechGapSnap does (V5)
- [X] T017 [P] [US1] JRT/transcribe/VoiceSilenceTest.kt: under 10 s of speech, one voice over 95%, or centroids closer than 0.6 give no names (V5, FR-004)

### Implementation for User Story 1

- [X] T018 [P] [US1] Write JR/transcribe/VoiceLink.kt (V4)
- [X] T019 [P] [US1] Write JR/transcribe/VoiceSeam.kt (V5), using the word times from `WhisperNative.segmentWords`
- [X] T020 [P] [US1] Write JR/transcribe/VoiceSilence.kt (V5's no-names rule)
- [X] T021 [US1] Keep each line's word times from the decode for MIX passes in JR/transcribe/TranscriptionEngine.kt (in memory only, not saved), mapped to the call's time
- [X] T022 [US1] Write JR/transcribe/VoiceStep.kt: decode the call to 16 kHz mono in `ChunkPlan`'s chunks, `VoiceNative.turns` per chunk, embed each chunk voice in windows of up to 60 s, link, apply the silence rule, label as Voice 1 (heard first) / Voice 2, cut with VoiceSeam; check free memory first; stop on abort or a deleted recording; log time, peak memory and the decision
- [X] T023 [US1] Call the voice step from JR/transcribe/TranscriptionRunner.kt after the MIX lines are saved, under the heavy lock, before the summary is queued; save the new labels and `voiceSource = voices` in one write; SPLIT calls log "voices skipped" and do nothing more (FR-002, SC-006)
- [X] T024 [US1] Show Voice 1 / Voice 2 in JR/ui/TranscriptScreen.kt in the You/Them colours, and "Telling voices apart..." while the step runs (contracts/ui.md)
- [X] T025 [US1] Add the Voices group's voice model row and the "one mix" note to JR/ui/TranscriptsSettingsScreen.kt (contracts/ui.md), offered only on phones with the memory (the note is the group's own and the footer, always shown; not tied to the last call)
- [X] T026 [US1] Copy and share write "Voice 1:" / "Voice 2:" (FR-014), in the transcript's share and copy code (done in T012: both go through SummaryRunner.linesOf)
- [X] T027 [P] [US1] Screen tests in JRT/ui/screens/: the transcript with Voice 1 / Voice 2 and the voice model row in its states; record with `-PrecordScreens=true` and look at every PNG
- [X] T028 [US1] Accuracy test JRA/transcribe/VoiceAccuracyTest.kt: Telegram recordings on the phone, channels mixed to one in memory, scored against the 10 dB frames (quickstart); run with `am instrument` on the A24, including a 20-30 min call; record results in V4/V5 and check SC-001 (95%)
- [ ] T029 [US1] On the A24 with the user: a real two-person phone call shows two voices matching what was said; from the app's log, the voice step's time (at most half the call, SC-003) and peak memory (under 500 MB, SC-004); a messenger call transcribed before and after comes out the same (SC-006); record what was measured in V5

**Checkpoint**: US1 complete; commit.

---

## Phase 4: User Story 2 - Your voice is You (Priority: P1)

**Goal**: the voice matching the owner's print is You, the other Them; otherwise Voice 1 / Voice 2 and the user can mark

**Independent Test**: with a recorded print, the user's one-mix call says You and Them; a call between two other people says Voice 1 / Voice 2 (spec US2)

### Tests for User Story 2

- [X] T030 [P] [US2] JRT/transcribe/VoiceNamesTest.kt: R16's numbers as fixtures (0.64 vs 0.17 gives You; 0.53 vs low gives You; 0.27 and 0.27 gives voices; no print gives voices; 0.46 vs 0.30 fails the 0.20 margin) (V6)
- [X] T031 [P] [US2] JRT/transcribe/OwnerPrintFromCallsTest.kt: a left side under 20 s, with speakerphone, whose windows disagree (cosine under 0.7) or match the right side (0.4 or more) makes no print; a good one adds a call; up to five calls kept, newest; a sample replaces calls (V7)

### Implementation for User Story 2

- [X] T032 [P] [US2] Write JR/transcribe/VoiceNames.kt (V6), thresholds as named constants with R16's numbers in their comments
- [X] T033 [P] [US2] Write JR/transcribe/OwnerPrintFromCalls.kt (V7's tests, plain)
- [X] T034 [US2] Write JR/transcribe/OwnerPrint.kt: the file in `noBackupFilesDir/voice/print.bin` (data-model.md), read, write, delete, void on another model id; confirm `data_extraction_rules` keeps no-backup out of backups
- [ ] T035 [US2] Find out whether the call's audio route (speakerphone) is known after a messenger call; if not, use the level-correlation fallback of V7; write which in V7 (for now the fallback is used: VoiceStep.learnFromCall passes speakerphone = null; the route is still to be looked for)
- [X] T036 [US2] After a SPLIT call is transcribed, feed its left side to OwnerPrintFromCalls when "Learn my voice from my calls" is on and no sample is recorded, in JR/transcribe/TranscriptionRunner.kt via VoiceStep's embedder
- [X] T037 [US2] Use VoiceNames in VoiceStep: You/Them and `voiceSource = print` when the print decides, else Voice 1 / Voice 2 (JR/transcribe/VoiceStep.kt)
- [X] T038 [US2] Add `RECORD_AUDIO` to app/src/main/AndroidManifest.xml, asked only on Record, with lint clean
- [X] T039 [US2] Write JR/ui/RecordVoiceScreen.kt: the text in the app's language, meter, 30 s ring, Start/Stop, under-20-s retry, audio kept in memory only, embedded and dropped (contracts/ui.md)
- [X] T040 [US2] Add the "Your voice" row and the "Learn my voice from my calls" switch (default on) to JR/ui/TranscriptsSettingsScreen.kt, with Record, Record again, Delete
- [X] T041 [US2] Add "Which one is you?" with Voice 1 / Voice 2 buttons, Undo, "Swap You and Them" and "Not marked" to JR/ui/TranscriptScreen.kt, writing through the DAO update of T013 (`marked`); a later print never touches a `marked` transcript
- [X] T042 [P] [US2] Screen tests in JRT/ui/screens/: the Your voice row in its three states, the Record screen, a transcript with "Which one is you?"; look at every PNG
- [ ] T043 [US2] On the A24 with the user (quickstart, owner's voice): ten one-mix calls with a recorded print, five between other people, three Telegram calls for a print from calls, R16's echo and played-audio calls making no print; write the numbers into V6/V7 and move the thresholds only with them (SC-002)

**Checkpoint**: US2 complete; commit.

---

## Phase 5: User Story 3 - The labels reach the summary and the CRM (Priority: P2)

**Goal**: summaries and Sales Engine transcripts use the new labels

**Independent Test**: with the Sales Engine mock, a one-mix work call sends Seller/Customer when named and Speaker 1/2 when not (spec US3)

- [X] T044 [P] [US3] In JR/summary/SummaryRunner.kt and its prompt, write Voice 1/2 as "Speaker 1" / "Speaker 2" and tell the model the user is one of them, unknown which; extend its test in JRT/summary/
- [X] T045 [P] [US3] In JR/crm/ContentText.kt label Voice 1/2 "Speaker 1" / "Speaker 2"; extend JRT/crm/ContentTextTest.kt
- [X] T046 [US3] Make sure the summary (and so a work call's content) is queued only after the voice step (T023); a mark after sending sends nothing (002's once rule): a test in JRT/crm/ against the work-call queue (holds by construction: TranscriptionWorker queues the summary after run() returns, the voice step is inside run(), and ContentHook reads the lines from the database when it sends; no new test)
- [ ] T047 [US3] Against the Sales Engine mock (tools/), a one-mix work call's transcript arrives with the right labels

**Checkpoint**: US3 complete; commit.

---

## Phase 6: User Story 4 - Old recordings (Priority: P3)

**Goal**: transcribing a past call again names its voices; nothing is redone by itself

**Independent Test**: an old unnamed one-mix transcript, transcribed again, has voices (spec US4)

- [ ] T048 [US4] Confirm the "transcribe again" path in JR/transcribe/TranscriptionRunner.kt runs the voice step and resets `voiceSource` (a marked transcript transcribed again starts over); a test in JRT/transcribe/
- [ ] T049 [US4] Make sure downloading the voice model or recording a print queues no work over old transcripts (JR/transcribe/model/ModelDownloadWorker.kt, JR/transcribe/OwnerPrint.kt); a test

**Checkpoint**: US4 complete; commit.

---

## Phase 7: Polish - every place that describes it

**Purpose**: CLAUDE.md's "When a feature lands" list; all must say the same

- [ ] T050 [P] README.md: the "What you can do" table, the FAQ (one-mix calls, the voice print), the phones table (A24, Pixel), the privacy and permission lines (microphone, the voice print), the tests badge and the developer section's test count
- [ ] T051 [P] tools/build_site.py: FEATURES/FEATURE_ROWS (a row with a picture), FAQ, "Will it work on your phone?", HOW_BODY, the manual's SETUP blocks (Settings > Voices, Record your voice, the transcript's labels, the memory notice), PRIVACY_DATA (the voice print as new data kept, the microphone, and their chips); then run it
- [ ] T052 [P] docs/ARCHITECTURE.md: a section "Who spoke in a one-mix call" with R16's and this feature's measurements, the contents line, the permission table (`RECORD_AUDIO`)
- [ ] T053 [P] docs/DEVELOPMENT.md and tools/README.md: the third submodule, test counts, the voice accuracy test's run command, the emulator check
- [ ] T054 [P] NOTICE.md and the About screen: sherpa-onnx, onnxruntime, pyannote segmentation-3.0, and the embedding model's attribution (T008)
- [ ] T055 Bump versionCode/versionName in gradle.properties; write fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (at most 500 characters) and update full_description.txt (at most 4000)
- [ ] T056 Demo data: a one-mix recording with Voice 1 / Voice 2 and one with You/Them by print in app/src/debug/java/com/jemcik/jemrec/diag/DemoRecordings.kt; a new screen in `SCREENS` of tools/build_site.py and `LISTING_ORDER` of tools/shoot.py with a name that sorts last; then run tools/shoot.py on the phone
- [ ] T057 Emulator check in tools/emulator_checks.py: a one-mix fixture of two synthetic voices made by the script gets Voice 1 / Voice 2 lines; run on the four AVDs two at a time, cold boot
- [ ] T058 `./gradlew lint`, `./gradlew :daemon-tests:errorProne`, and every unit and screen test pass

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2 → US1**: the engine, then the model and labels, then the voice step.
- **US2** needs US1's VoiceStep (T022, T023). Its plain parts (T030-T033) can start once Phase 2 is done.
- **US3** needs US1 (labels exist); US2 only for You/Them by print, which it handles like split calls.
- **US4** needs US1.
- **Polish** after the stories it describes.

### Parallel opportunities

- Phase 2: T009, T011, T014 together after T008.
- US1: T015-T017 together, then T018-T020 together; T027 beside T028.
- US2: T030-T033 together; T042 beside T043.
- US3: T044 and T045 together.
- Polish: T050-T054 together.

### Parallel example: User Story 1

```text
T015 VoiceLinkTest   T016 VoiceSeamTest   T017 VoiceSilenceTest
T018 VoiceLink       T019 VoiceSeam       T020 VoiceSilence
```

## Implementation Strategy

- **MVP = Phase 1, 2 and US1**: one-mix calls get Voice 1 / Voice 2. Useful alone: turns are readable and the summary knows there are two people.
- **Then US2**: You and Them, the part the user and the Sales Engine want; the thresholds are only final after T043's measurements.
- **Then US3 and US4**, then Polish; the version bump is merged only when everything says the same.
