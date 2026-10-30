# Tasks: Transcripts and summaries of calls

**Input**: Design documents from `specs/001-transcripts-summaries/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. CLAUDE.md requires decisions to be tested off the phone
and screen tests for new screens (spec SC-006).

## Conventions for every task

- `CV/` means `/Users/bohdanplastun/StudioProjects/CallVault/app/src/main/java/com/baba/callvault/`;
  `CVT/` means `/Users/bohdanplastun/StudioProjects/CallVault/app/src/test/java/com/baba/callvault/`;
  `CVA/` means `/Users/bohdanplastun/StudioProjects/CallVault/app/src/androidTest/java/com/baba/callvault/`.
- `JR/` means `app/src/main/java/com/jemcik/jemrec/` in this repo; `JRT/` means
  `app/src/test/java/com/jemcik/jemrec/`.
- **Port** = copy the CallVault file, change the package to the JemRec one
  named, replace `AppLogger` with `android.util.Log` (tag `JemRec`),
  `AppPreferences` with `JR/transcribe/TranscribeSettings.kt`, and the
  `cv_`/`CV:` prefixes with `jr_`/`JR:`. Keep comments that carry a
  measurement and say it was CallVault's, on which phone. Drop what the task
  says to drop. Keep CallVault's GPL header for now (licensing is settled
  later).
- Every decision in plain Kotlin, no Android types, so it can be unit tested
  (CLAUDE.md). Comments say why, in plain words.
- Status line before any device step (CLAUDE.md). Device: Galaxy A24,
  serial `R58X30P75XV`. Never commit call audio.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

**Purpose**: the native build and new dependencies, nothing user-visible.

- [x] T001 Add submodules `third_party/whisper.cpp` (https://github.com/ggml-org/whisper.cpp.git, checkout `371b5a75`) and `third_party/llama.cpp` (https://github.com/ggml-org/llama.cpp.git, checkout `a298422da7`) in `.gitmodules`
- [x] T002 Create `app/src/main/cpp/CMakeLists.txt` from CallVault's `app/src/main/cpp/CMakeLists.txt` without the `audiohandoff` target; targets `whisperjr` (from `whisperjr.cpp`, linking `whisper`) and `llamajr` (from `llamajr.cpp`, linking `llama`); keep `GGML_BACKEND_DL ON`, `GGML_CPU_ALL_VARIANTS ON`, `GGML_OPENMP OFF`, tests/examples/server/tools off, `-O3` in Debug; `WHISPER_ROOT`/`LLAMA_ROOT` point at `../../../../third_party/...`
- [x] T003 [P] Copy `CallVault/app/src/main/cpp/whispercv.cpp` to `app/src/main/cpp/whisperjr.cpp`, renaming every `Java_com_baba_callvault_transcription_WhisperNative_` to `Java_com_jemcik_jemrec_transcribe_WhisperNative_` (contract: [contracts/native-bridges.md](contracts/native-bridges.md))
- [x] T004 [P] Copy `CallVault/app/src/main/cpp/llamacv.cpp` to `app/src/main/cpp/llamajr.cpp`, renaming `Java_com_baba_callvault_summary_LlamaNative_` to `Java_com_jemcik_jemrec_summary_LlamaNative_`
- [x] T005 In `app/build.gradle.kts`: `ndkVersion = "28.2.13676358"`, `defaultConfig { ndk { abiFilters += "arm64-v8a" } }`, `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }`, `packaging { jniLibs { useLegacyPackaging = true } }` only if `GGML_BACKEND_DL` needs the libs extracted (CallVault's comment says it does: keep its comment); a comment saying why native code now exists and that it is arm64 only
- [x] T006 Add Room 2.8.4 (`room-runtime`, `room-ktx`, `room-compiler` via KSP, `room-testing` for tests) and the KSP plugin 2.3.9 to `build.gradle.kts` and `app/build.gradle.kts`; `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`; verify KSP works with AGP 9.4's built-in Kotlin by building; if not, pick the KSP release that matches Kotlin 2.3.21 and note it in research.md R8
- [x] T007 Add `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` and androidTest dependencies (`androidx.test:runner`, `androidx.test.ext:junit`) in `app/build.gradle.kts` for the JNI and migration tests
- [x] T008 [P] Copy `CallVault/app/src/main/assets/ggml-silero-v5.1.2.bin` to `app/src/main/assets/ggml-silero-v5.1.2.bin`; add Silero VAD (MIT), whisper.cpp (MIT), llama.cpp (MIT) to `NOTICE.md` and to `app/src/main/assets/licenses/`
- [x] T009 Build `./gradlew assembleDebug`; record APK size before and after in research.md R12; `./gradlew lint` passes with no warnings

**Checkpoint**: the app builds with both native libraries and Room.

---

## Phase 2: Foundational

**Purpose**: what every story needs. No story starts before this phase ends.

### Measure the phone first

- [x] T010 On the A24 (status line first): ask the user for one test phone call (each side speaking in turn, nothing private), pull only that recording from `Recordings/JemRec` to the scratchpad, and compare its left and right channels (correlation of 20 ms levels, with a short script in the scratchpad, not in the repo). Write the result, device and date into `docs/ARCHITECTURE.md` ("Recording, and being asked", the two-channels paragraph) and research.md R4; delete the pulled file afterwards. Real calls are not read for this
- [x] T011 On the A24: log `WhisperNative.systemInfo` and `LlamaNative.systemInfo` once from a debug receiver; confirm DOTPROD is listed; record in research.md R1

### Native wrappers

- [x] T012 [P] Port `CV/transcription/WhisperNative.kt` to `JR/transcribe/WhisperNative.kt` (`System.loadLibrary("whisperjr")`)
- [x] T013 [P] Port `CV/summary/LlamaNative.kt` to `JR/summary/LlamaNative.kt` (`System.loadLibrary("llamajr")`)
- [x] T014 Create `JR/transcribe/EngineLock.kt`: one process-wide `Mutex` both engines take, so at most one model is loaded (data-model "Jobs")
- [x] T015 Port `CV/transcription/TranscriptionEngine.kt` to `JR/transcribe/TranscriptionEngine.kt`, taking `EngineLock` around load and run
- [x] T016 Port `CV/summary/SummaryEngine.kt` to `JR/summary/SummaryEngine.kt`, taking `EngineLock`
- [x] T017 [P] Port `CV/transcription/VadModel.kt` to `JR/transcribe/VadModel.kt`

### Settings

- [x] T018 Create `JR/transcribe/TranscribeSettings.kt` over `JR/Prefs.kt` with every key in data-model "Settings" and its default; plain getters/setters; measured `tx_rtf.<model>` and `tx_load_ms.<model>`
- [x] T019 [P] Unit test `JRT/transcribe/TranscribeSettingsTest.kt`: defaults, round trips, unknown model id falls back to the catalogue default

### Models

- [x] T020 [P] Port `CV/transcription/model/DownloadableModel.kt` to `JR/transcribe/model/DownloadableModel.kt`
- [x] T021 Create `JR/transcribe/model/SpeechModel.kt` from `CV/transcription/model/TranscriptionModel.kt`: entries `SMALL_Q5_1`, `LARGE_V3_TURBO_Q8_0` (CallVault's values), `NB_SMALL_Q5_0` (url `https://huggingface.co/NbAiLab/nb-whisper-small/resolve/main/ggml-model-q5_0.bin`, size 175209680, sha256 `2a9025afb6e825fc4ae6a46671e0cb2f43e62f1dec87270deea6fe61b5285a20`, languages `{"no"}`), `NB_MEDIUM_Q5_0` (`NbAiLab/nb-whisper-medium`, 539212484, `18733de634af639a43b0f8c5f5a2ea0920de4c5b32a5570ec130981581c0e5e7`, `{"no"}`); fileName unique per entry (`nb-small-q5_0.bin`, `nb-medium-q5_0.bin`); drop `LARGE_V3_TURBO_Q5_0` (no install has it); `recommendedFor(bigCores: Int)` per research R3
- [x] T022 Create `JR/summary/SummaryModel.kt` from `CV/summary/SummaryModel.kt`: `GEMMA_4_E2B_QAT_Q4_K_XL` (CallVault's values), `QWEN3_1_7B_Q4_K_M` (url `https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf`, 1107409472, `b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897`, peakMemoryBytes 1_800_000_000 marked as an estimate until T079 measures it), `GEMMA_3_1B_Q4_K_M` (`unsloth/gemma-3-1b-it-GGUF/.../gemma-3-1b-it-Q4_K_M.gguf`, 806058272, `8270790f3ab69fdfe860b7b64008d9a19986d8df7e407bb018184caa08798ebd`, peak 1_300_000_000 estimate); `fits(totalMem)` = `totalMem >= 2 * peakMemoryBytes`; `bestFor(totalMem)`
- [x] T023 [P] Unit test `JRT/summary/SummaryModelTest.kt`: on 5.8 GB only Qwen and Gemma 3 fit; on 12 GB Gemma 4 is best; ids unique (adapt `CVT/summary/SummaryModelTest.kt`)
- [x] T024 Port `CV/transcription/model/ModelRepository.kt` to `JR/transcribe/model/ModelRepository.kt` for both catalogues, files in `noBackupFilesDir/models/`; drop transfer between installs; add debug-only listing of `getExternalFilesDir("models")/*.bin|*.gguf` as imported models (id `imported:<name>`, no hash, debug build only, a comment saying why)
- [x] T025 Port `CV/transcription/model/ModelDownloadPolicy.kt` and `CV/transcription/model/ModelDownloadWorker.kt` to `JR/transcribe/model/`: Range resume, size then SHA-256, rename into place, Wi-Fi only from `models_wifi_only`; drop `ModelTransfer*`. *Done as CallVault does it: no foreground notification - a stopped download resumes from its partial file, which suits gigabyte files better*
- [x] T026 [P] Port tests `CVT/transcription/model/ModelRepositoryTest.kt`, `ModelDownloadPolicyTest.kt`, `ModelPruneTest.kt` to `JRT/transcribe/model/`

### Storage

- [x] T027 Create Room entities in `JR/transcripts/db/` per data-model: `TranscriptEntry.kt`, `TranscriptSegmentEntry.kt` (+ `TranscriptSegmentFts` FTS4 unicode61), `CallSummaryEntry.kt` (+ `CallSummaryFts`), `RecordingLanguageEntry.kt`; shapes from `CV/data/transcripts/db/` of the same names, columns as data-model (speaker column added, flags/tags/notes/labels/favourites dropped)
- [x] T028 Create DAOs `JR/transcripts/db/TranscriptDao.kt`, `CallSummaryDao.kt`, `RecordingLanguageDao.kt` from CallVault's `TranscriptDao.kt` and `CallSummaryDao.kt`: insert/replace a whole transcript in one transaction, observe by recording, delete by recording, FTS match queries
- [x] T029 Create `JR/transcripts/db/TranscriptDatabase.kt` (version 1, `transcripts.db`, schema exported); build once so `app/schemas/.../1.json` exists
- [x] T030 Create `JR/transcripts/TranscriptRepository.kt` from `CV/data/transcripts/TranscriptRepository.kt`: save, load, delete, observe, `hasTranscript(names)`
- [x] T031 [P] Unit tests (Robolectric, in-memory Room) `JRT/transcripts/TranscriptRepositoryTest.kt`: save replaces, cascade deletes segments and summary, FTS finds a word in Norwegian with `æøå` and in Ukrainian
- [x] T032 Delete a recording's transcript, summary and language override when the recording is gone. *Done as `TranscriptRepository.prune`, called beside `Waveforms.prune` in `JR/ui/MainViewModel.kt` under the same guard, instead of hooks in `Trash.kt`: it also covers Android emptying its own trash and recordings removed by another app; tests `JRT/transcripts/TranscriptPruneTest.kt` and `TranscriptRepositoryTest.kt`*

### Heavy work plumbing

- [x] T033 Port `CV/transcription/HeavyWorkNotification.kt` to `JR/transcribe/HeavyWorkNotification.kt`: channel "Transcripts and summaries" created where JemRec creates channels (`JR/capture/CallMonitorService.kt` area pattern), progress and Cancel
- [x] T034 In `app/src/main/AndroidManifest.xml`: declare `androidx.work.impl.foreground.SystemForegroundService` with `tools:node="merge"`, `android:foregroundServiceType="specialUse"` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property saying "on-device transcription and summaries of the user's recordings"
- [x] T035 [P] Port `CV/transcription/TranscriptionProgress.kt`, `TranscriptionProgressTracker.kt`, `TranscriptionInFlight.kt` to `JR/transcribe/` with their tests from `CVT/transcription/` to `JRT/transcribe/`

**Checkpoint**: native engines load on the A24, models download and verify, storage works, nothing visible yet.

---

## Phase 3: User Story 1 - Read what was said (P1) 🎯 MVP

**Goal**: a recording's Transcript screen transcribes it on the phone and shows You / Them lines that seek playback.

**Independent Test**: on the A24 with a speech model downloaded, transcribe a recorded call (quickstart steps 3-4) and tap a line.

### Tests for User Story 1

- [x] T036 [P] [US1] Unit test `JRT/transcribe/AudioInTest.kt`: one channel picked from interleaved stereo; 48 kHz to 16 kHz length and a 1 kHz tone survives, an 7.9 kHz tone is attenuated (port the relevant cases of `CVT/transcription/AudioDecoderTest.kt`)
- [x] T037 [P] [US1] Unit test `JRT/transcribe/ChannelSidesTest.kt`: identical channels → mix; one silent → split; different speech levels → split; mono file → mix; forced settings win
- [x] T038 [P] [US1] Port `CVT/transcription/ChunkPlanTest.kt` and `SpeechGapSnapTest.kt` to `JRT/transcribe/`
- [x] T039 [P] [US1] Unit test `JRT/transcribe/TurnMergeTest.kt`: You and Them lines merged by start, ties You first, blank text dropped
- [x] T040 [P] [US1] Port `CVT/transcription/TranscriptionRunnerTest.kt` and `TranscriptionEstimateTest.kt` to `JRT/transcribe/` with a fake `Transcriber`

### Implementation for User Story 1

- [x] T041 [P] [US1] *Done as `JR/transcribe/AudioDecoder.kt`, CallVault's decoder ported whole (its length guards and tests come with it) with a `Channel` choice added, rather than over `PcmDecoder`.* Originally: create `JR/transcribe/AudioIn.kt`: decode with `JR/capture/PcmDecoder.decode` and resample with the code of `CV/transcription/AudioDecoder.kt` (`pcm16ToMono16k`, `antiAliasKernel`, `resampleTo16k`), adapted to take one channel index or a downmix; decode a time range for chunking
- [x] T042 [P] [US1] Create `JR/transcribe/ChannelSides.kt`: from 20 ms levels of the first 60 s of sound (reuse the level arithmetic in `JR/capture/Waveforms.kt`), correlation threshold decides split or mix; `tx_sides` overrides; threshold set from T010's file with the reason in a comment
- [x] T043 [P] [US1] Port `CV/transcription/ChunkPlan.kt` and `CV/transcription/SpeechGapSnap.kt` to `JR/transcribe/`
- [x] T044 [P] [US1] Port `CV/transcription/TranscriptSegment.kt` to `JR/transcribe/TranscriptSegment.kt` adding `speaker: Speaker` (`YOU`, `THEM`, `NONE`)
- [x] T045 [US1] Create `JR/transcribe/TurnMerge.kt` (T039)
- [x] T046 [US1] Port `CV/transcription/TranscriptionRunner.kt` to `JR/transcribe/TranscriptionRunner.kt`: per recording, decide sides (T042), for each side chunk (T043), decode (T041), run the engine (T015) with the VAD path (T017), stitch, label, merge (T045), save (T030) with model, language, sides, audioMs, wallMs, loadMs, threads; drop CallVault's `SpeakerLabeller`, `SpeakerSeamSplit`, `CapturedSpeakerTurns` (the channels replace them); store measured RTF and load time in `TranscribeSettings`
- [x] T047 [P] [US1] Port `CV/transcription/TranscriptionEstimate.kt` to `JR/transcribe/` (estimate from audio length, measured RTF, split doubling only the speech share)
- [x] T048 [US1] *Written as one `TranscriptionWorker` on a single appended chain (`jr_tx_now`), one run at a time, progress polled once a second, Cancel aborting whisper; no separate queue class.* Port `CV/transcription/TranscriptionQueue.kt` and `CV/transcription/TranscriptionWorker.kt` to `JR/transcribe/`: unique work `jr_tx_now`, input = recording names, foreground via `HeavyWorkNotification`, Cancel calls `WhisperNative.requestAbort()`, a cancelled run saves nothing; threads from `tx_threads` or the measured default (T096; until then the number of cores above the slowest cluster's frequency)
- [x] T049 [US1] *As `TranscriptionWorker.enqueue` and `cancel`; the scheduler class arrives with US4.* Port `CV/transcription/TranscriptionScheduler.kt` `runNow` and `stopNow` only to `JR/transcribe/TranscriptionScheduler.kt` (modes come in US4)
- [x] T050 [US1] *State lives in `JR/ui/TranscriptsController.kt` beside MainViewModel, as AnswerController's does; MainViewModel gains `Screen.TRANSCRIPT`, `openTranscript` and `playFrom`.* In `JR/ui/MainViewModel.kt`: `Screen.TRANSCRIPT` (up = HOME) with `transcriptOf: String?`; open/close; state flows for the open recording's transcript, job progress and model state; `transcribe(recording)`, `cancelTranscription()`, `playFrom(recording, ms)` (= `play` then `seekTo`)
- [x] T051 [US1] Create `JR/ui/TranscriptScreen.kt` per [contracts/ui.md](contracts/ui.md): top bar, the Transcript tab's states (no model with Download, ready with estimate, running with part i/n and Cancel, done, failed with Try again), lines with time, You/Them in the look's colours (from `JR/ui/theme/`), tap to play from the line, long press to copy; footer with model, language and time taken
- [x] T052 [US1] In `JR/ui/MainScreen.kt`: route `Screen.TRANSCRIPT` to `TranscriptScreen`, like `TrashScreen`
- [x] T053 [US1] In `JR/ui/RecordingRows.kt` `RowActions`: a Transcript icon button (content description "Transcript") before Share, calling `vm.openTranscript(recording)`; works for the 36 dp and 40 dp call sites
- [x] T054 [P] [US1] Add demo transcripts for two demo recordings in `app/src/debug/java/com/jemcik/jemrec/diag/DemoRecordings.kt` (invented people, one split, one mixed)
- [x] T055 [US1] Screen tests in `JRT/ui/screens/TranscriptScreenshots.kt` (pattern of `HomeScreenshots.kt`): each Transcript-tab state in each of the three looks, light and dark; record with `-PrecordScreens=true` and look at every PNG
- [x] T056 [US1] *Run with `adb shell am instrument`, not Gradle's connected task, which uninstalls the app after; 5 of 5 on the A24, 2026-10-29.* Instrumented test `app/src/androidTest/java/com/jemcik/jemrec/transcribe/WhisperBridgeTest.kt` from `CVA/transcription/TranscriptionEngineInstrumentedTest.kt`: a generated tone gives no text; a bundled 5 s English public-domain clip gives expected words (skip when no model is on the device)
- [ ] T057 *Deferred to the end: needs calls made by the user (2026-10-29).* [US1] On the A24 (status line first): quickstart steps 2-4; record wall time, RTF, peak memory for `small-q5_1` on a 5 min English file in research.md R14

**Checkpoint**: MVP - any recording can be transcribed and read.

---

## Phase 4: User Story 2 - The call's language (P1)

**Goal**: 17 languages plus automatic; a per-recording override; NB-Whisper for Norwegian.

**Independent Test**: transcribe the same Norwegian file with NB-Whisper and turbo; set Ukrainian for one recording only.

### Tests for User Story 2

- [x] T058 [P] [US2] Port `CVT/transcription/TranscriptionLanguageChoiceTest.kt`, `LanguageScriptTest.kt`, `WrongScriptRetryTest.kt`, `TranscriptionPromptTest.kt` to `JRT/transcribe/`; add cases: `no` is supported, a Norwegian phone (`nb`, `nn`, `no` locales) defaults to `no`, an unsupported phone language defaults to `no`
- [x] T059 [P] [US2] Unit test `JRT/transcribe/ModelForLanguageTest.kt`: Norwegian with NB downloaded → NB; with it not downloaded → the general model; English → the general model; `tx_language_models` off → the general model

### Implementation for User Story 2

- [x] T060 [P] [US2] Port `CV/transcription/TranscriptionLanguageChoice.kt` to `JR/transcribe/TranscriptionLanguageChoice.kt` (name kept, to port its tests): `SUPPORTED` = CallVault's 16 + `"no"`; `defaultLanguage` maps `nb` and `nn` to `no` and falls back to `no` (fork default) instead of null
- [x] T061 [P] [US2] Port `CV/transcription/LanguageScript.kt`, `WrongScriptRetry.kt`, `TranscriptionPrompt.kt`, `DecodeSettings.kt` to `JR/transcribe/`; Norwegian expects Latin script
- [x] T062 [US2] *Done as `JR/transcribe/ModelChoice.kt`.* Create `JR/transcribe/ModelForLanguage.kt` (T059) and use it in `TranscriptionRunner` (T046)
- [x] T063 [US2] Wire the per-recording override: `RecordingLanguageDao` read in `TranscriptionRunner`; `TranscriptionLanguage.resolve(chosen, setting)`; wrong-script retry with the pinned language
- [x] T064 [US2] In `JR/ui/TranscriptScreen.kt` overflow: **Language for this call** (17 by their own names via `Locale(code).getDisplayLanguage(Locale(code))`, sorted, plus Detect automatically) and **Transcribe again**
- [x] T065 [US2] Create `JR/ui/TranscriptsSettingsScreen.kt` (first part): Language, Speech model list with size/state/Download/Delete and the "Made for Norwegian" and "Recommended for this phone" marks, Use a model made for the language, Who spoke; `Screen.TRANSCRIPTS` (up = SETTINGS) in `MainViewModel.kt`, routed in `MainScreen.kt`, entry "Transcripts and summaries" in `JR/ui/SettingsList.kt`
- [x] T066 [US2] Screen tests in `JRT/ui/screens/SettingsScreenshots.kt` for the new settings screen; record and look at every PNG
- [ ] T067 [US2] On the A24: quickstart step 4 for each of the 17 languages with FLEURS clips (outside the repo), small and turbo; Norwegian also NB small and NB medium; WER per language and model into research.md R14; if NB wins but is too slow, quantise it to q8_0 on the Mac (whisper.cpp `quantize`), try it through the debug import, and note the result

**Checkpoint**: every language transcribes in its own words; Norwegian has its own model.

---

## Phase 5: User Story 3 - What the call was about (P1)

**Goal**: a structured summary, in the transcript's language, with points linked to their lines; only models that fit are offered.

**Independent Test**: on the A24 summarise a Norwegian and an English transcript; every fact is in the transcript; tap a point.

### Tests for User Story 3

- [x] T068 [P] [US3] Port `CVT/summary/CallSummaryTest.kt`, `CallSummaryMergeTest.kt`, `SummaryChunkingTest.kt`, `SummaryCitationsTest.kt`, `SummaryDedupeTest.kt`, `SummaryGrammarTest.kt`, `SummaryLanguageTest.kt`, `SummaryPromptTest.kt`, `SummaryQueueTest.kt`, `SummaryRunnerTest.kt`, `SummaryTextTest.kt`, `SummaryProgressTest.kt` to `JRT/summary/`; add: `no` resolves from the transcript's language and the prompt says "Norwegian (Bokmål)"; a Qwen prompt switches thinking off
- [x] T069 [P] [US3] *As `SummarySamples` + `SummaryFaithfulnessTest` (English, Norwegian, Ukrainian), run with `am instrument`.* Port `CVA/summary/SummarySamples.kt` and `SummaryModelLoadInstrumentedTest.kt` to `app/src/androidTest/java/com/jemcik/jemrec/summary/`; add a Norwegian and a Ukrainian sample with known figures (invented content)

### Implementation for User Story 3

- [x] T070 [P] [US3] Port `CV/summary/CallSummary.kt`, `SummaryGrammar.kt`, `SummaryChunking.kt`, `SummaryCitations.kt`, `SummaryDedupe.kt`, `SummaryText.kt`, `SummaryProgress.kt` to `JR/summary/`; citations point at `transcript_segments.idx`
- [x] T071 [P] [US3] Port `CV/summary/SummaryLanguage.kt` to `JR/summary/` (Norwegian comes from the transcript's language; it cannot come from script)
- [x] T072 [US3] Port `CV/summary/SummaryPrompt.kt` to `JR/summary/`: add `"no" to "Norwegian (Bokmål)"` to `LANGUAGE_NAMES`; speaker lines rendered as "You:" / "Them:" when split; per-model chat template and Qwen's `/no_think`
- [x] T073 [US3] *Runner and worker written after CallVault's, on the transcriptions' chain; scheduler with US4.* Port `CV/summary/SummaryRunner.kt`, `SummaryQueue.kt`, `SummaryWorker.kt`, `SummaryScheduler.kt` to `JR/summary/`: unique work `jr_sum_now`; before loading, check `ActivityManager.MemoryInfo.availMem` against `peakMemoryBytes` and fail with "Not enough free memory right now" instead of starting; save through `CallSummaryDao`
- [x] T074 [US3] *In `TranscriptsController`.* In `JR/ui/MainViewModel.kt`: summary state for the open recording, `summarise`, `cancelSummary`, `showLines(indices)`
- [x] T075 [US3] In `JR/ui/TranscriptScreen.kt`: the Transcript | Summary segmented button; Summary tab states per contracts/ui.md (offer with size and memory need; "Needs a phone with 8 GB" for one that does not fit; Summarise; running; done with Why, the account, Key points, Decisions, To do, Facts; tap an item → Transcript tab scrolled to and highlighting its lines)
- [x] T076 [US3] In `JR/ui/TranscriptsSettingsScreen.kt`: Summary model list (fitting ones only, others greyed with the reason), Download/Delete
- [x] T077 [P] [US3] Demo summaries for the demo recordings in `app/src/debug/java/com/jemcik/jemrec/diag/DemoRecordings.kt`
- [x] T078 [US3] Screen tests for the Summary tab states in `JRT/ui/screens/TranscriptScreenshots.kt`; record and look at every PNG
- [x] T079 [US3] *Done 2026-10-29; Gemma 3 1B dropped, Qwen3 1.7B kept (research.md R14).* On the A24: run T069's samples with Qwen3 1.7B and Gemma 3 1B; record time, peak memory (`dumpsys meminfo`), and every invented or wrong fact, per language, in research.md R14; set each model's `peakMemoryBytes` from the measurement; if one invents facts in a language, do not offer summaries in that language on that model (a rule in `SummaryModel` with the evidence)

**Checkpoint**: P1 complete - transcripts in 17 languages and summaries.

---

## Phase 6: User Story 4 - Without asking each time (P2)

**Goal**: after each call, or overnight while charging; summarise too.

**Independent Test**: switch on After each call, make a call, find its transcript without opening the app.

- [x] T080 [P] [US4] *As `TranscriptionSchedulerTest` (JemRec's own scheduler; CallVault's queue and notice policy were not taken).* Port `CVT/transcription/TranscriptionSchedulerTest.kt`, `TranscriptionQueueTest.kt`, `TranscriptionNoticePolicyTest.kt` to `JRT/transcribe/`
- [x] T081 [US4] *After-call work is expedited; overnight takes the last 30 days, newest first (research.md R9).* Complete `JR/transcribe/TranscriptionScheduler.kt` from `CV/transcription/TranscriptionScheduler.kt`: `apply` (manual cancels the sweep; overnight periodic 24 h with charging (from `tx_charging_only`), battery not low, storage not low, newest first), `transcribeAfterCallIfEnabled`; call `apply` at app start and when the setting changes
- [x] T082 [US4] In `JR/capture/RecordingSaver.kt` `save`: after a recording is saved (not for Busy, Gone, Skipped, NotAnswered, GreetingHangUp, GreetingFailed outcomes), call `TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name)`
- [x] T083 [US4] *As `JR/transcribe/SummaryAfterTranscript.kt`, called by the transcription worker.* In `JR/summary/SummaryScheduler.kt`: after a transcription finishes and `sum_auto` is on and a fitting model is ready, enqueue the summary
- [x] T084 [US4] *As `JR/transcribe/TranscriptNotices.kt`, with a TRANSCRIPT deep link; only for automatic runs.* Port `CV/transcription/TranscriptNotifier.kt` and `TranscriptionNoticePolicy.kt` to `JR/transcribe/`: "Transcript ready" opening the Transcript screen (deep link through `JR/DeepLink.kt`), failures in plain words
- [x] T085 [US4] In `JR/ui/TranscriptsSettingsScreen.kt`: When (Only when I ask / After each call / Overnight while charging), Charging only, Summarise after each transcript
- [ ] T086 *Deferred to the end: needs calls made by the user (2026-10-29).* [US4] On the A24: quickstart steps 7-8 (after-call transcript appears; a call during a transcription is recorded complete, SC-005)

---

## Phase 7: User Story 5 - Find and take out (P2)

**Goal**: search finds words in transcripts and summaries; Transcribed chip; copy; export.

**Independent Test**: search a word only in a transcript; the row shows with the words.

- [x] T087 [P] [US5] *Written here: `TranscriptSearchTest`, a repository search test on SQLite, and ListChipsTest cases.* Port `CVT/data/transcripts/PageSearchTest.kt` and `SearchIndexingTest.kt` to `JRT/transcripts/`; add a test in `JRT/ui/ListChipsTest.kt` for the Transcribed chip (offered only when it leaves some, not all, not none)
- [x] T088 [US5] Create `JR/transcripts/TranscriptSearch.kt` from `CV/data/transcripts/PageSearch.kt`: FTS query over segments and summaries → recording names with a snippet; escape user input for FTS
- [x] T089 [US5] In `JR/ui/MainViewModel.kt` and `JR/ui/HomeScreen.kt`: merge transcript hits into the Recordings search; a row matched by text shows the snippet with the words highlighted under its details line
- [x] T090 [US5] *The chip; no mark on the row (it would redraw every list for a fact the chip already gives).* In `JR/ui/ListChips.kt`: `ChipKind.TRANSCRIBED` ("Transcribed"), facts from `TranscriptRepository.hasTranscript`; in `JR/ui/RecordingRows.kt` the small transcribed mark and the in-progress ring in the details line
- [x] T091 [P] [US5] *Shared from the cache through the existing FileProvider; `TranscriptExportTest` written here.* Port `CV/data/transcripts/export/TranscriptFormat.kt`, `TranscriptExport.kt`, `TranscriptExportFile.kt`, `ExportLabels.kt` to `JR/transcripts/export/` (TXT, Markdown, SRT, VTT, JSON) with speaker names; share through the existing `FileProvider` (`app/src/main/res/xml/file_paths.xml` gets an `exports` cache path); port their tests from `CVT/data/transcripts/` if present, else write `JRT/transcripts/export/TranscriptExportTest.kt` (SRT/VTT timing format, Markdown escaping)
- [x] T092 [US5] *As buttons under the transcript, beside Transcribe again: the screen's bar has no overflow.* In `JR/ui/TranscriptScreen.kt` overflow: Copy all, Export (format picker → share sheet)
- [x] T093 [US5] Screen tests: a search with a transcript snippet and the Transcribed chip in `JRT/ui/screens/HomeScreenshots.kt`; record and look at every PNG

---

## Phase 8: User Story 6 - Keep it tidy (P3)

**Goal**: sizes, Delete, space used, Wi-Fi only.

- [x] T094 [US6] In `JR/ui/TranscriptsSettingsScreen.kt`: Download over Wi-Fi only; Space used (models from file sizes, transcripts from the database file); debug-only Imported models
- [x] T095 [US6] Screen tests for the finished settings screen; record and look at every PNG

---

## Phase 9: Polish and every place that describes it

**Purpose**: CLAUDE.md "When a feature lands", measurements, gates.

- [x] T096 *6 threads fastest on the A24 (research.md R14); the default stays.* Measure threads on the A24 (2, 4, 8) for the default speech model; set the default in `TranscribeSettings` with the figures in a comment and research.md R14
- [x] T097 *Small for two fast cores, turbo from four, kept; Qwen's peak set from the A24; turbo on the A24 still to measure with the calls.* Set `SpeechModel` and `SummaryModel` defaults per phone class from R14; update estimates (`realTimeFactor`, `peakMemoryBytes`) with A24 figures and dates
- [x] T098 [P] `docs/ARCHITECTURE.md`: a section "Transcripts and summaries" (engines, models and why, channels as speakers with the A24 result, languages, memory gate, where data lives, network use), the contents line, the permission table's `INTERNET` row, and the "no library, no native code" sentence in "Playing a recording" corrected
- [x] T099 [P] `README.md`: "What you can do" rows, FAQ (does audio leave the phone, how big are the models, which languages), privacy and permission lines (models are downloaded from Hugging Face), tests badge and developer test count
- [x] T100 [P] *The new feature sits in the home row beside Play, keep, clean up until its own picture exists (T103).* `tools/build_site.py`: `FEATURES`, `FEATURE_ROWS` (with a picture), FAQ, "Will it work on your phone?" (memory for summaries), `HOW_BODY`, `SETUP` (a manual section, Settings, notifications table), `PRIVACY_DATA` words and chips (model downloads, transcripts kept on the phone); then run `python3 tools/build_site.py`
- [x] T101 [P] `docs/DEVELOPMENT.md` and `tools/README.md`: submodules, NDK, native build, test counts, instrumented tests, emulator check
- [x] T102 [P] `fastlane/metadata/android/en-US/full_description.txt` (stay at or under 4000 characters: shorten elsewhere), `short_description.txt` if the one-liner changes, `changelogs/<new versionCode>.txt` (under 500 characters); bump to 0.26.0 in `gradle.properties` by its rule
- [ ] T103 *Deferred to the end, with the Honor: tools/shoot.py reshoots every screen on the Honor (1256 px); shot on the A24, every picture would change size (2026-10-29).* `tools/build_site.py` `SCREENS` and `tools/shoot.py` `LISTING_ORDER`: the Transcript screen and the Summary tab as new names that sort last; run `tools/shoot.py` on the phone with the debug build's invented people
- [ ] T104 *Waits on the user: the AVDs CLAUDE.md names are not on this machine, and creating one is theirs to allow.* `tools/emulator_checks.py`: a check that a recorded emulator call transcribes with `small-q5_1` (pushed through the debug import) and yields text; ask the user before creating the AVDs CLAUDE.md names if they are still missing
- [x] T105 CLAUDE.md: a line under "Build and test" on the submodules and the NDK, and that models are never committed
- [ ] T106 *Done 2026-10-29 but the emulator checks: 1108 app and 196 daemon JVM tests, lint, Error Prone, the bridge test on the A24.* Gates: `./gradlew testDebugUnitTest`, `./gradlew lint` (no warnings), `./gradlew :daemon-tests:errorProne`, instrumented tests on the A24, `python3 tools/emulator_checks.py` per CLAUDE.md; fix what fails
- [ ] T107 *Deferred to the end: needs calls made by the user (2026-10-29).* Run [quickstart.md](quickstart.md) end to end on the A24; tick SC-001 to SC-006 in spec.md with the figures, measured and inferred kept apart

---

## After review (2026-10-30, the user's requests)

- [x] T108 Defaults: When - After each call, Summarise after each transcript on; nothing queued after a call without a speech model (`TranscribeSettings`, `TranscriptionScheduler`)
- [x] T109 Language detected by default, before the run, per side, then pinned with its model (`LanguageGuess`, `TranscriptionEngine.detectLanguages`, `whisperjr.cpp` `detectLanguage`, `TranscriptionRunner`); measured on the A24 in research.md R15
- [x] T111 A stopped transcription or summary stops its engine: the worker's ticker asks for the abort as it is cancelled (it was cancelled before it could, and a cancelled run went on 97 s on the A24)
- [x] T112 Kept whole by `HeavyQueue` (no work fails; Cancel marks instead of cancelling). Cancelling a waiting transcription leaves a cancelled work in the `jr_tx_now` chain, and the next one queued then replaces the chain (`APPEND_OR_REPLACE`), stopping the one running (A24, 2026-10-30)
- [ ] T110 *Not in this feature: its own spec; measured in R16.* Speaker diarisation for phone calls whose two channels carry one mix, as the A24's do

## Dependencies and execution order

- **Setup (T001-T009)** → **Foundational (T010-T035)** → stories.
- **US1 (T036-T057)** needs Foundational. It is the MVP.
- **US2 (T058-T067)** needs US1's runner and screen.
- **US3 (T068-T079)** needs US1's transcripts (storage, screen); independent of US2.
- **US4 (T080-T086)** needs US1 (and US3 for auto summaries).
- **US5 (T087-T093)** needs US1 (and US3 for summary search).
- **US6 (T094-T095)** needs US2's settings screen.
- **Polish (T096-T107)** after the stories; T096-T097 need US1-US3 on the phone.
- T010 (channels on the A24) needs one test call from the user; T042's threshold waits for it.

## Parallel examples

- Setup: T003, T004, T008 together.
- Foundational: T012, T013, T017, T019, T020, T023, T026 together; T031 and T035 once their code exists.
- US1: tests T036-T040 together; then T041, T042, T043, T044, T047 together.
- US3: T068 and T069 together; then T070 and T071.
- Polish: T098-T102 together.

## Implementation strategy

1. Setup and Foundational, then **US1** - stop, measure on the A24 (T057),
   show the user.
2. **US2** and **US3** - the P1 set; measure languages and summaries (T067,
   T079); defaults from evidence.
3. **US4**, **US5**, **US6**.
4. Polish: every place that describes it, gates, version 0.26.0.
