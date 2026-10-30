# Research: transcripts and summaries

Sources: CallVault at `5cfa2c60` (2.4.7), read 2026-10-29; JemRec at `43b2273`
(0.25.1); the Hugging Face API and the Galaxy A24 over adb, both queried
2026-10-29. A figure that is CallVault's measurement on another phone says so.
None of it has been measured on the A24 yet: those numbers come from the
measurement tasks and go in R14.

## R1. The test phone sets the budget

Samsung Galaxy A24, SM-A245F, Android 14, MediaTek Helio G99: 2x Cortex-A76
at 2.2 GHz and 6x A55 at 2.0 GHz; CPU features include `asimddp` (DOTPROD)
and not i8mm; 5.8 GB memory; 91 GB free (adb, 2026-10-29).

CallVault measured on an OP9 Pro (Snapdragon 888, 1x X1 + 3x A78, DOTPROD,
no i8mm) and an OP12. The A24 has two big cores to the 888's four, older
ones, so expect transcription about two to three times slower than CallVault's
OP9 figures. That is an **inference**, not a measurement. It decides two
things before any measuring:

- **Measured on the A24, 2026-10-29** (debug build, diag op `engines`): both
  engines load, and ggml picks the DOTPROD kernels for both - whisper
  `NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | DOTPROD = 1 | REPACK = 1`, llama the
  same; no MATMUL_INT8, as the CPU has no i8mm. Memory: 5934 MB total, 3093 MB
  available with the phone idle - room for Qwen3 1.7B's estimated 1.8 GB, not
  for Gemma 4 E2B's 3.5 GB.
- The speech default on this phone may have to be the small tier, not turbo.
- CallVault's summariser (Gemma 4 E2B, 3.5 GB peak by their measurement) on a
  5.8 GB phone leaves Android about 2 GB, and a background kill is likely. A
  smaller summariser is needed (R6).

## R2. Speech engine

- **Decision**: whisper.cpp as a git submodule at CallVault's pin (`371b5a75`,
  2026-08-20); CMake through the NDK; arm64-v8a only; CallVault's
  `whispercv.cpp` bridge with its JNI symbols renamed to
  `com.jemcik.jemrec.transcribe.WhisperNative`. `GGML_CPU_ALL_VARIANTS` and
  `GGML_BACKEND_DL` on, so the DOTPROD kernels load on the A24.
- **Rationale**: it is what CallVault ships and measured, and the bridge
  already solves abort mid-run, progress, VAD, word timings, and the trap that
  `detect_language = true` means "exit after detecting" in whisper.cpp.
- **Alternatives**: Android's `SpeechRecognizer` (no files, no offline
  guarantee); Vosk / sherpa-onnx (weaker on most of the 17 languages, nothing
  to take from CallVault); a cloud API (the call leaves the phone).

## R3. Speech models

Hugging Face API, 2026-10-29, and CallVault's `TranscriptionModel`:

| Id | File | Size | Note |
|---|---|---|---|
| `small-q5_1` | ggerganov/whisper.cpp `ggml-small-q5_1.bin` | 190,085,487 | CallVault's Fast tier: Hebrew as gist; RTF 0.38 on OP9 Pro |
| `turbo-q8_0` | ggerganov/whisper.cpp `ggml-large-v3-turbo-q8_0.bin` | 874,188,075 | CallVault's default; RTF 0.72 on OP9 Pro, 1258 MB peak |
| `nb-small-q5_0` | NbAiLab/nb-whisper-small `ggml-model-q5_0.bin` | 175,209,680 | Norwegian only; Apache-2.0 |
| `nb-medium-q5_0` | NbAiLab/nb-whisper-medium `ggml-model-q5_0.bin` | 539,212,484 | Norwegian only |

SHA-256 values are in the API answer and go into the catalogue as CallVault's
do. `turbo-q5_0` is left out: CallVault measured it 1.98x slower than q8_0 at
no gain (Q5 has no accelerated ARM path in ggml; Q8_0 has the DOTPROD one).

- **Decision**: catalogue the four. Default by phone and measurement (R14):
  start with `small-q5_1` on a phone with fewer than four big cores, otherwise
  `turbo-q8_0`. For Norwegian, an NB-Whisper model is used when downloaded
  ("made for this language" in Settings), the general one otherwise.
- **NB-Whisper's quant**: only q5_0 and f16 are published, and q5_0 takes the
  slow path. If NB is the more accurate but too slow, convert it to q8_0 with
  whisper.cpp's `quantize` on the Mac, host it where testers can reach it
  (the fork's own release assets), and put that URL and digest in the
  catalogue. A debug-only import from `Android/data/<app>/files/models/` lets
  this be tried before hosting.
- **Languages**: whisper knows all 17; CallVault's list is a UI list, not an
  engine limit. Norwegian is `no`.

## R4. Who spoke

- **Decision**: per recording, measure how alike the two channels are over
  the first 60 s of speech (correlation of the 20 ms levels JemRec's waveform
  code already computes). Different: transcribe each channel alone, left
  **You**, right **Them**, merge by time. Alike, or mono: downmix, no names. A
  setting can force either.
- **Rationale**: JemRec writes stereo with the sides apart for messenger
  calls and mixer-routed phone calls, always (it places them), and for
  ordinary phone calls on the Honor (measured, ARCHITECTURE "Recording, and
  being asked"); a Pixel on Android 17 gives the same mix on both (issue #20,
  reported). CallVault records mono and needed a speaker labeller for a worse
  answer.
- **Measured on the A24, 2026-10-29** (two test calls, decoded on the Mac,
  copies deleted after):

  | Call | Length | Sample correlation | 20 ms level correlation | One side >10 dB louder | Decision |
  |---|---|---|---|---|---|
  | Telegram, incoming | 16.2 s | -0.002 | -0.46 | 92% of frames with sound | split |
  | Phone, outgoing | 9.0 s | 1.000 | 1.000 | 0% (largest difference 0.002 FS) | mix |

  So on this phone a **phone call has no You / Them** from its channels: the
  driver copies one mix to both. Messenger calls (and mixer-routed phone
  calls) do. One phone call is one sample; repeat on a longer call before
  calling it settled. CallVault's labeller would not help here: it also reads
  turns from a stereo capture whose channels differ.
- **Threshold**: the two cases are far apart, so the decision is not fine:
  split when the 20 ms level correlation over frames with sound is below 0.9
  and one side leads by 10 dB in at least a third of them; mix otherwise.
- **Later, not in this feature**: telling voices apart in a mixed call needs
  on-device speaker diarisation (voice embeddings and clustering, e.g.
  sherpa-onnx's); a separate spec if phone-call labels matter on such phones.
- Compute does not double: whisper's VAD skips each channel's silence.

## R5. Audio in

- **Decision**: JemRec's `capture/PcmDecoder` (MediaCodec, Opus) into
  CallVault's resampler (`AudioDecoder.pcm16ToMono16k` and its anti-alias
  kernel, adapted to pick one channel), chunks of 5 minutes with 10 s overlap
  and CallVault's `ChunkPlan` stitching.
- **Rationale**: JemRec's decoder is already tested on its files; chunking
  caps memory (an hour is 230 MB of 16 kHz float per channel otherwise).

## R6. Summariser

Candidates (Hugging Face API, 2026-10-29):

| Id | File | Size | Licence | Note |
|---|---|---|---|---|
| `gemma4-e2b-q4kxl` | unsloth/gemma-4-E2B-it-qat-GGUF `gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf` | 2,620,370,976 | Apache-2.0 | CallVault's: invented nothing in four controlled runs; 3.5 GB peak (OP12) |
| `qwen3-1.7b-q4km` | unsloth/Qwen3-1.7B-GGUF `Qwen3-1.7B-Q4_K_M.gguf` | 1,107,409,472 | Apache-2.0 | Multilingual (119 languages claimed); thinking switched off by the prompt |
| `gemma3-1b-q4km` | unsloth/gemma-3-1b-it-GGUF `gemma-3-1b-it-Q4_K_M.gguf` | 806,058,272 | Gemma terms | Smallest; Google describes 1B as English-focused, so likely weak on the rest |

- **Decision**: llama.cpp as a submodule at CallVault's pin (`a298422da7`),
  CallVault's `llamacv.cpp` bridge renamed. Offer a model only when the
  phone's total memory is at least twice its peak (Gemma 4 E2B: 8 GB phones
  and up). On the A24 that leaves Qwen3 1.7B; Gemma 3 1B is measured as the
  fallback. Before offering any, CallVault's faithfulness check (R7) is run
  on it.
- **Rationale**: CallVault chose by faithfulness, and an invented fact in a
  record of a real call is the worst output; but its choice does not fit this
  phone. Q4_K keeps the repacked ARM kernels (CallVault's note).
- **Risk**: a 1.7B model invents more. If it fails the check in a language,
  that language's summary is not offered on small phones rather than offered
  wrong.

## R7. Summary content and checks

- **Decision**: take CallVault's `summary/` package: `CallSummary` (intent,
  summary, key points, decisions, action items, key facts), `SummaryPrompt`
  with a pinned output language, `SummaryGrammar` (GBNF so the output is
  always valid JSON), `SummaryChunking` (6,000-character chunks, then a summary
  of summaries), `SummaryCitations` (each point names its line numbers),
  `SummaryDedupe`. Add Norwegian ("Norwegian (Bokmål)") to the prompt's
  language names and to `SummaryLanguage` (Latin script: it cannot be detected
  from letters, so it comes from the transcript's language).
- **Faithfulness check**: CallVault's `SummarySamples` (androidTest) - a
  transcript with known figures, run through the model, every figure checked.
  Extended with a Norwegian and a Ukrainian sample.

## R8. Storage and search

- **Decision**: Room (2.8.4, KSP) with CallVault's shape trimmed:
  `transcripts`, `transcript_segments` with an FTS4 table (unicode61),
  `call_summaries` with FTS4 of `searchableText()`. Keyed by recording name.
  Schemas exported to `app/schemas/`.
- **Rationale**: search over hundreds of transcripts needs an index; CallVault
  has the schema, the DAO and migration tests to take. Waveforms stay in their
  cache files; nothing else in JemRec moves into Room.
- **Check**: Room's KSP processor with AGP 9.4's built-in Kotlin (JemRec has
  no `kotlin-android` plugin); CallVault uses KSP 2.3.9.

## R9. Running heavy work

- **Decision**: CallVault's split: `TranscriptionQueue` / `SummaryQueue` decide
  order; `TranscriptionWorker` / `SummaryWorker` (`CoroutineWorker`,
  `setForeground` with the `specialUse` type JemRec already holds) run one job
  at a time under a shared engine lock; `HeavyWorkNotification` with progress
  and Cancel; `TranscriptionScheduler` for the three modes (manual, after each
  call, overnight with charging + battery not low + storage not low).
  After-call hook: where `RecordingSaver` publishes a recording.
- **Started without the screen** (2026-10-29): a transcription queued from the
  background runs without its foreground service - Android refuses it - and
  the A24 ran a call 13 times slower that way (R14). After-call work is
  therefore queued from the call's own service, as the recording is saved,
  and as **expedited** work, which JobScheduler runs at once with a
  foreground's priority even where the foreground service is refused. If
  Android stops it (an expedited job's time is limited), the work is tried
  again later rather than failed; a Cancel by the user is not retried.
  Not yet measured with a real call: tasks T086.
- **The chain is kept whole** (2026-10-30, `HeavyQueue`): WorkManager fails
  everything queued behind a failed work, cancels everything behind a
  cancelled one, and APPEND_OR_REPLACE onto a chain holding either starts it
  anew, cancelling the work running. On the A24 a Cancel on a waiting call
  and a tap on Transcript stopped another call's transcription. So no work in
  the chain fails (an error is a success's output, the newest ended run is
  what a screen shows) and Cancel never goes through WorkManager: it marks
  the work, which ends itself as stopped, running or when it starts.
- **Threads**: measured on the A24 with 2, 4 and 8 (two fast cores and six
  slow ones; more threads may be slower). Default from the measurement.

## R10. Models on the phone

- **Decision**: CallVault's `ModelRepository` + `ModelDownloadWorker` +
  `ModelDownloadPolicy` (Wi-Fi only option, Range resume, size then SHA-256,
  rename into place). Models under `noBackupFilesDir/models/`. No transfer
  between installs (CallVault's `ModelTransfer*`) - not needed for an internal
  fork.
- **Network**: JemRec's first use of the internet beyond 127.0.0.1. The
  permission table, README, the privacy policy in `build_site.py`
  (`PRIVACY_DATA`) and the site's chips change.

## R11. Interface

- **Decision**: JemRec's look, not CallVault's screens. A Transcript icon in
  `RowActions` (shared by the three looks) opens a full **Transcript** screen,
  as Trash does: a Transcript / Summary switch at the top (Material 3
  segmented button), the lines below, the summary as sections. Recordings
  rows with a transcript show a small mark; search hits show the matched
  words under the row. Settings gets **Transcripts and summaries**.
- **Rationale**: one screen fits all three looks without touching each
  player; it is how JemRec already adds depth (Trash, Answering machine).

## R12. Toolchain

- NDK installed: `28.2.13676358`; CallVault built with `27.2.12479018`. Pin
  the installed one; fall back only if the submodules do not build.
- APK size, measured 2026-10-29: the release APK is 11.98 MB with both
  engines (9.75 MB before, `app/build.gradle.kts`, 2026-10-23; armeabi-v7a
  dropped at the same time). Native libraries 15.8 MB uncompressed, of which
  seven `libggml-cpu-android_*.so` variants at about 4.7 MB each before
  stripping. Unstripped they were 142 MB and the debug APK 95 MB, so only
  Maven's libraries keep their symbols now.
- `./gradlew lint` stays a gate; `:daemon-lint` and Error Prone are unaffected
  (the daemon is untouched).
- Emulator checks: CLAUDE.md names AVDs `jemrec-api31-0.14`, `jemrec-api33`,
  `jemrec-api36`, `jemrec-api37.2`; this machine lists `Pixel_10_Pro` and
  `Pixel_10_Pro_Fold` only (2026-10-29). Ask before creating any.

## R13. Version

- 0.26.0, versionCode per `gradle.properties`' rule; changelog under 500
  characters; `full_description.txt` is at 4004 bytes, so a line has to go for
  each line added.

## R15. The language, detected before the run

- **Decision** (2026-10-30, the user asked for detect as the default): when
  no language is set, each side's first minute is decoded once, its louder
  seconds (within 25 dB of its loudest, above -50 dBFS) up to 30 s go to
  whisper's own language detection (`whisper_lang_auto_detect`, one encoder
  pass) on the model chosen for the call; an answer at 0.5 or more in one of
  the 17 is pinned, with the model made for it (NB-Whisper for Norwegian).
  Sides told apart are transcribed each in its language; a side with no
  speech in that minute takes the other's; one whisper is unsure of is left
  to whisper's detection pass by pass. The transcript is filed under the
  language of the side that said more, which the summary is written in.
- **Rationale**: the model is chosen before whisper runs, so whisper's own
  detection could never pick NB-Whisper; and whisper left on auto heard a
  Russian side as English (R14). Pinned after detection, whisper is told.
- **Measured** on the Galaxy A24 (2026-10-30), the user's calls, side by
  side (LEFT is the phone's owner); probabilities of the likeliest:

  | Call | Side | Whisper small | large-v3-turbo |
  |---|---|---|---|
  | 10:08 Russian, 16 s | left | ru 0.66 | ru 0.995 |
  | | right, a few words | pt 0.45 (ru 0.05) | ru 0.78 |
  | 10:13 Russian, phone, 8 s | both (one mix) | ru 0.77 | ru 0.96 |
  | 14:13 pinned English | left | ru 0.90 | ru 0.99 |
  | | right, 5 s of speech | en 0.30 | es 0.28, pt 0.26, ru 0.24 |
  | 14:21 Norwegian | left | no 0.80 | no 0.82 |
  | 14:46 Russian | left | ru 0.94 | ru 1.0 |
  | | right | ru 0.66 | ru 0.39 |
  | 16:29 English | left | en 0.995 | en 1.0 |
  | 10:05 (30th) Russian | left / right | ru 0.97 / 0.92 | - |
  | 10:06 (30th) Russian with English words | left | en 0.43, ru 0.29 | ru 0.97 |
  | 11:17 (30th) Ukrainian, phone, 221 s | one mix | - | uk 0.996 |

  A side with no speech in its first minute (14:21, 16:29 and 10:06 on the
  right) gave nothing to tell by.
- **Turbo, not the smallest model**: small was wrong twice where turbo was
  right (10:08 right, 10:06 left) and unsure where turbo was sure; turbo was
  less sure once (14:46 right, left to whisper). Cost per side: 23 s on
  turbo, 11.5 s on small. The 14:13 call explains its nonsense: the user's
  side is Russian (0.99), transcribed under an English pin. The 11:17 call,
  made by the user after the change, ran through it end to end: detected
  (140 s, of which about a minute waiting for the engine), pinned, 88 lines
  in Ukrainian in 547 s on turbo, and its summary in Ukrainian.
- **Only with no speech, not "among the guesses"**: taking the other side's
  language whenever whisper listed it would have written 14:13's English
  news as Russian (turbo had Russian third, 0.24).
- **Decoding is the slow part**: Opus decodes at about four times real time
  on the A24 (113 s of stereo in 27-34 s, with or without draining the codec
  eagerly - tried, no change), so the first minute is decoded once for every
  side; a decode per side over two minutes made detection 69-85 s.
- **Alternatives**: whisper's own detection in the run (cannot choose the
  model, re-detects per pass); the smallest model (measured above); the
  phone's language as the default pin (the previous default; one answer for
  calls in several languages).

## R16. Voices in a one-mix call (a spike for a later spec)

A phone call on the A24 carries one mix on both channels (R4), so its lines
have no You or Them. Measured 2026-10-30 whether on-device diarisation can
give them; nothing of it is in the app.

- **Engine**: sherpa-onnx 2.0.1 (Apache-2.0): pyannote segmentation-3.0
  int8 (1.5 MB, MIT) + a speaker-embedding model + clustering fixed at two
  speakers. Run from Python on the Mac for accuracy, and its arm64 binary on
  the A24 for speed (with Termux's libc++ 30: the release's binaries need a
  newer libc++ than NDK 28's).
- **Accuracy against the channels** (Telegram calls, left and right mixed to
  one, frames where one side leads by 10 dB as the truth; "of" is the share
  of those frames diarisation labelled at all). Only one call is a real
  two-sided conversation: 14:46, 217 s, 83 s against 22 s of speech -
  97-99% right with every embedding model, wideband and cut to the phone
  band alike. The rest are test calls (speakerphone echo, news or a talk
  played into the phone, or one speaker), where the channel truth itself is
  unsure; they said little.
- **The user's phone calls, by content** (no truth to score against):
  11:17, Kyivstar's voice menu - every line of the user's ("Тест... раз,
  два, три", "Зірочка", twice) in one voice, all of the menu in the other,
  bar a few lines split 50/50 where the two overlap. 11:44, Norwegian, two
  synthetic voices talking to each other (the user, 2026-10-30) - the lines
  read out and the tutor's split by content as expected, but lines whisper
  merged across a turn come out 45/55: they would need cutting at the turn
  by word times (CallVault's SpeakerSeamSplit).
- **Embedding models** (TitaNet-small, CAM++, ERes2Net x2, ResNet34): the
  same on the 14:46 call. NeMo TitaNet-small (40 MB) is the fastest; it
  takes at most about 120 s at once, so longer speech is embedded in windows.
- **Speed and memory on the A24**: the 252 s call 11:44 in 76-101 s with
  TitaNet (RTF 0.30-0.40, 4 or 6 threads alike), 349 MB peak RSS; ERes2Net
  base RTF 0.82. On top of the transcription, which is 2-3 s per second of
  call there.
- **Which voice is the owner's**: a print from the left channel of the
  user's own calls (10:05 and 10:06 on the 30th) against each voice, cosine:
  11:17 - the user 0.64, the menu 0.17; 10:13 - the user 0.53; 11:44 -
  both 0.27, rightly no answer: neither voice was the user's. And the left channel is not always the owner's own
  voice: in the test calls it carried played audio (prints 0.2-0.46 from
  call to call, one at 0.85 with the other side, an echo). So a print would
  need a clean source - a short recording the user makes once, or only
  calls whose left side is plainly a person - and a margin, with "Voice 1 /
  Voice 2" where it is not met.
- **Size**: libonnxruntime.so is 15 MB for arm64 in sherpa-onnx's own build;
  the models (about 42 MB) download on a tap like the others.

## R14. Measurements (filled by the measurement tasks)

**The user's calls, 2026-10-29, Galaxy A24** (five Telegram calls; read by
hand, copies deleted after):

- *Norwegian* (153 s, a talk on the Norwegianisation of the Sami read out):
  Whisper small heard "sanene" for "samene", "storkingen" for "Stortinget",
  "for norskningspolitikken"; NB-Whisper small got all three right, and
  "sikkerhetstrussel", "Nord-Norge", "et eneste ord på norsk" - 300 s for the
  call. On speakerphone their sentences came twice, the second time through
  your microphone: LineCleanup now drops a line of yours that is mostly their
  words at the time (some still pass, cut differently by NB-Whisper).
- *English* (167 s): your side was whisper writing "I'm going to the pool."
  six times over quiet, unclear sound; the repeats now go, but what is left of
  that side is still not what was said - most likely another language on
  that side with English pinned (to ask the user). Their side, news read out,
  came out readable.
- *Russian* (217 s, a question to a voice assistant): right but for a few
  words.
- *English, 46 s*: under a second of sound in it; "no speech", rightly.
- *Summary of the Norwegian call*: refused at 2.1-2.4 GB free against the
  2.6 GB peak - now the peak less the weights' file (1.8 GB). Then written in
  English despite "Norwegian (Bokmål)" in the prompt, with three action items
  nobody gave. The same instruction in Norwegian, at the start and again after
  the transcript, and a rule on what decisions and action items are, gave a
  Norwegian summary with none (155 s).
- *The samples again with that prompt*: English right and complete; but in
  Norwegian "assembly is included" (it costs 2 000 extra), and in Ukrainian
  "15:30" for half past two. The first prompt had both right and failed the
  real call. Qwen3 1.7B summarises well and errs in details now and then:
  the screen says to check what matters; an 8 GB phone gets Gemma 4.

**Threads, 2026-10-29, Galaxy A24** (`ThreadsBenchmark`, Whisper small, the
9.0 s test call transcribed as two sides, run as an instrumented test so at
a foreground's priority):

| Threads | Wall | Load |
|---|---|---|
| 2 | 74.7 s | 1.3 s |
| 4 | 64.4 s | 0.3 s |
| 6 | 56.4 s | 0.4 s |
| 8 | 66.9 s | 0.5 s |

Six - every core but two, CallVault's rule from its 6+2 OnePlus - is the
fastest here too; with all eight the six A55s hold the two A76s back at
each of ggml's barriers. The default stays. Settings' thread count is for
a phone that measures otherwise.

Speech default on the A24: Whisper small, the recommendation for a phone
with two fast cores. Turbo was not run here; by CallVault's figures it is
about twice small's work, so a five-minute call would take about ten
minutes - the edge of SC-001. To measure with the calls at the end.

**Summaries, 2026-10-29, Galaxy A24** (`SummaryFaithfulnessTest`: three
invented two-minute calls whose every fact is listed - English, Norwegian,
Ukrainian - through the app's prompt, grammar and checks; read by hand for
anything invented):

| Model | Language | Time (load incl.) | Resident | Result |
|---|---|---|---|---|
| Gemma 3 1B | en | 90.7 s | 1529 MB | invented a detail ("the engineer ... at the hospital"), points were bare timestamps |
| Gemma 3 1B | no | 96.2 s | 1592 MB | written in English, a delivery window nobody gave (14:00-18:00), JSON broken off |
| Gemma 3 1B | uk | 3.2 s | 1478 MB | nothing |
| Qwen3 1.7B | en | 106-126 s | 2412-2479 MB | right; Mark, 90 pounds, Friday the fifteenth, the valve at 40 |
| Qwen3 1.7B | no | 92-139 s | 2422-2514 MB | in Norwegian, right: Thursday, 12 000 + 2 000 = 14 000, Storgata 14; left out the 9-11 window, the driver's call, card payment |
| Qwen3 1.7B | uk | 95-101 s | 2442-2523 MB | in Ukrainian, right figures (800, 300, Wednesday 2:30); left out Dr Koval; one clumsy sentence |

- Nothing invented by Qwen3 1.7B in any language; omissions, not
  fabrications. Gemma 3 1B is not offered.
- Qwen's points were transcript lines copied whole, speaker and all, and the
  Ukrainian "facts" were bare timestamps: SummaryTidy now drops both. The
  second run above is with it: the copies and stamps were gone, the rest the
  same (greedy decoding).
- Qwen's peak is 2.5 GB, not the 1.8 GB estimated, so `peakMemoryBytes` is
  2.6 GB: by the twice-the-peak rule a 5.2 GB phone and up, which the A24
  (5.9 GB) is. The run itself checks free memory before loading.
- Gemma 4 E2B could not be run here (it does not fit); CallVault's
  measurements stand for it on 8 GB phones.

**2026-10-29, Galaxy A24, debug build, Whisper small q5_1, 6 threads.**

- Download: 181.3 MB in 70 s (2.6 MB/s), digest matched, installed.
- The 9.0 s phone test call (one mix, so `MIX`), language detected: 447 s
  wall, 1.1 s of it the model load - a real-time factor near 50. The text was
  right: one line of Russian, the test's own words. But the run was started
  from adb with the phone locked, so Android refused the foreground service
  (`startForegroundService() not allowed due to mAllowStartForeground false`)
  and the app ran as a background process; on Samsung that means the small
  cores and little of them. So this measures what happens *without* the
  foreground service, not the phone's speed. The next measurement is from
  the app on screen (the Transcript button), and that figure is the one for
  SC-001.
- Consequence for automatic modes (US4): a transcription started from the
  background must be started while the app may still start a foreground
  service - from the call's own service right after a call, or as expedited
  work - or it runs at this speed.

**2026-10-29, same phone and model, the app on screen** (foreground service
granted, no refusal in the log):

| Call | Audio | Sides | Wall | Load | Lines |
|---|---|---|---|---|---|
| Phone, test | 9.0 s | mix | 33.6 s | 0.4 s | 1, right (Russian, detected) |
| Telegram, test | 16.2 s | split | 59.5 s | 0.3 s | 5: 4 the user's, 1 the other side's |

- In the foreground the 9 s call took 33.6 s against 447 s locked: the
  foreground service is worth about 13x on this phone.
- Both are below whisper's 30 s window, which it always processes whole, so
  these are the cost of one window per side - about 30 s each on the A24 -
  and say little yet about a long call (SC-001 needs a 5 minute one).
- The other side's only line, 0.24 s in, was English words nobody said.
  Measured again with each line's levels: that side was loud there (-9.4
  dBFS), so it was not whisper writing over silence but Russian heard as
  English, the language left to detection. With Russian pinned the same
  recording gave "А на раз, два, три. А на раз, два, три." for that side
  (82.9 s). So: pin the language; detection is the fallback, and Settings
  says it is slower and sometimes wrong. A strict quiet-side guard
  (QuietSide) stays for a channel that is truly silent; it dropped nothing
  here.
- The A24's transcripts were first saved under the wrong recording: all
  transcriptions share one WorkManager chain, which merges each finished
  work's output into the next one's input, and the output carried the
  recording's name. Fixed (output keys apart from input keys, with a test);
  the two runs above were made after the fix, back to back in one chain.

Per model on the A24: wall time, load time, real-time factor, peak memory
(`dumpsys meminfo`), threads; word error rate per language on FLEURS clips;
summary time, peak memory and a hand check of every fact. Device and date on
each.
