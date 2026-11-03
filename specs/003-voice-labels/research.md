# Research: Who spoke in a one-mix call

What was measured comes from 001's research R4 and R16 (Galaxy A24, One UI,
Android 14, 2026-10-29/30). The rest is decided here, with what still has to
be measured before it is relied on marked **Measure**.

## V1. The engine and how it gets into the APK

- **Decision**: sherpa-onnx (Apache-2.0) as a third submodule in
  `third_party/`, pinned to v2.0.1 (the version R16 measured), built by the
  app's CMake with NDK 28.2 like whisper.cpp and llama.cpp: its C API only,
  with ASR, TTS, keyword spotting and the rest switched off. A small JNI
  bridge of our own, `app/src/main/cpp/voicejr.cpp`, beside `whisperjr.cpp`
  and `llamajr.cpp`, exposes three calls: tell voices apart in a buffer, embed
  a buffer, free. onnxruntime comes as the official prebuilt
  `libonnxruntime.so` for arm64 (sherpa-onnx's build downloads it), about
  15 MB (R16).
- **Rationale**: the project's rule is native engines from pinned source with
  our NDK. R16 ran the release's own arm64 binaries only with Termux's libc++,
  because they need a newer libc++ than NDK 28's. A build from source with
  NDK 28 avoids that. The C API carries the segmentation, embedding and
  clustering together, so none of it is rewritten.
- **Measured 2026-11-03 (T001)**: sherpa-onnx v2.0.1 takes onnxruntime
  1.28.2 from csukuangfj/onnxruntime-libs (not Microsoft's AAR).
  `llvm-readelf -d` on its arm64 `libonnxruntime.so` (22.2 MB, not the
  15 MB R16 noted): NEEDED is only `libdl`, `liblog`, `libm`, `libc`; no
  `libc++_shared`, and no undefined `std::__ndk1` symbols, so its libc++ is
  linked in statically. Built with NDK r27d (clang 18.0.4), API 27. sherpa-onnx
  reaches it only through onnxruntime's C API (`OrtGetApiBase`), so no C++
  types cross between its libc++ and NDK 28's: the prebuilt library fits as
  it is, and nothing has to be built from source. R16's libc++ trouble was
  sherpa-onnx's own release binaries; built here with NDK 28 they do not
  arise. The static variant (`libonnxruntime.a`, 1.5 GB with LTO objects;
  zip sha256 `bc68410f...13c6c0`) is not used: the shared one is simpler and
  the same code.
- **Alternatives**: sherpa-onnx's Android AAR (Kotlin API included): the same
  libc++ problem, and a second copy of the Kotlin wrappers we would not use.
  A diariser in ggml, beside whisper: none exists that is maintained.
  pyannote via Python: not on a phone.
- **F-Droid**: this fork ships as an APK (002), so a prebuilt onnxruntime is
  acceptable here. If the feature goes upstream to F-Droid, onnxruntime has to
  be built from source: noted in DEVELOPMENT.md, not done now.

## V2. The models

- **Decision**: pyannote segmentation-3.0, int8 (1.5 MB, MIT), and NeMo
  TitaNet-small as the voice embedding (about 40 MB). Shown as **one** voice
  model in Settings (two files, one size, one download button), each file
  with its own sha256 as the other models. Downloaded from sherpa-onnx's
  release assets (`speaker-segmentation-models`,
  `speaker-recongition-models`, the tag's own spelling), unpacked from their
  archive on the phone if they come only as an archive.
- **The files** (downloaded 2026-11-03): `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2`
  from tag `speaker-segmentation-models`, 6,958,444 bytes, sha256
  `24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488`,
  holding `model.int8.onnx` (1,540,506 bytes, sha256
  `d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d`; MIT,
  CNRS 2022); `nemo_en_titanet_small.onnx` from tag
  `speaker-recongition-models`, 40,257,283 bytes, sha256
  `ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e`. The segmentation model comes only in the
  archive, so the phone unpacks it (or it is mirrored as a plain file:
  decided in T009).
- **Rationale**: R16 found every embedding model the same on the one real
  call (97-99%), and TitaNet-small the fastest (RTF 0.30-0.40 on the A24
  with the segmentation, against 0.82 for ERes2Net base).
- **Licence (T008, read 2026-11-03)**: NVIDIA's NGC catalog entry for
  TitaNet-S (`api.ngc.nvidia.com/v2/models/nvidia/nemo/titanet_small`, version
  1.19.0) says its use is covered by the NeMo Toolkit's licence, which is
  Apache License 2.0. It ships; NOTICE.md names it, and the Apache text is in
  the app's licence assets. CAM++ is not needed.
- **Long calls**: TitaNet takes at most about 120 s at once (R16), so a
  voice's speech is embedded in windows of up to 60 s and averaged.

## V3. Where it runs in the transcription

- **Decision**: in `TranscriptionRunner.run`, after the lines are made and
  cleaned (`TurnMerge`, `LineCleanup`) and only when the call is MIX
  (`ChannelSides.decide`, unchanged). The lines are saved first, unnamed, as
  today; then the voice step runs under the same `EngineLock` / heavy queue,
  and the transcript's labels are updated in one write. A stop, a deleted
  recording, too little memory or a failure leaves the unnamed transcript.
- **As built (T023)**: the runner takes a `VoiceTeller` (VoiceStep in the
  app, a stand-in in its tests, none by default); `saveVoices` writes only if
  the transcript is still the one made at that `createdAt`, so a transcript
  made again or deleted meanwhile is not written over. The summary is queued
  after `run` returns, so it already follows the voice step. The transcript
  screen shows names by `voiceSource`, not by `split` as before.
- **Rationale**: FR-009 (never later than without it). The heavy queue
  already keeps whisper and the summary model apart; the voice step joins it,
  so it never runs beside a summary (SC-004). The summary waits for the voice
  step when one is due, so it is written with the names.
- **Alternatives**: before whisper (diarise, then transcribe per voice):
  doubles whisper's work over the turns and breaks its context; and whisper
  is the slow part (2-3 s per second on the A24).

## V4. Long calls in bounded memory

- **Decision**: the call is decoded to 16 kHz mono in the chunks
  `ChunkPlan` already makes (5 min, 10 s overlap). Each chunk is told apart
  on its own into up to two voices, with each voice's mean embedding. Chunks
  are then linked in plain Kotlin (`VoiceLink`): a chunk's voice joins the
  call's voice whose embedding it is closest to by cosine (two chunk voices
  as the better of the two pairings; a lone one as the second call voice when
  under 0.6 of the only one so far), and the call's voice is the mean of what
  joined it, weighted by speech. The overlap's turns are not used to settle a
  tie, as first planned: nothing measured needs it yet (T028). A voice's
  first 60 s in a chunk is embedded; under 1.5 s, none. Most calls are one
  chunk.
- **Rationale**: a 1-hour call whole is 230 MB of float samples before the
  model's own memory; a chunk is 19 MB. R16's 349 MB peak was a 252 s call,
  so one chunk.
- **Measured in the app, 2026-11-03 (T006, `VoiceBridgeTest`, A24, debug
  build)**: R16's 14:46 Telegram call (216 s), decoded by the app's own
  decoder with both channels mixed to one: two voices, 22 turns, 17.0 s
  and 127.3 s of speech, in 87.7 s and 90.6 s on two runs (RTF 0.40-0.42;
  R16 0.30-0.40 from the command line). Memory, from the process's
  `/proc/self/status`: 255 MB before the models (the test process and the
  decoded call), 394 MB with both loaded (+139 MB: the embedding model is
  loaded twice, once in the diariser and once for prints), 614 MB peak
  during the run (+220 MB). So the voice engine itself is about 360 MB,
  as R16's 349 MB; SC-004's 500 MB is about the step, not the process.
  Decoding the 216 s Ogg took 61 s in that debug run, by itself - worth a
  look apart from this feature.
- **Accuracy, 2026-11-03 (T028, `VoiceAccuracyTest`, A24, debug build)**:
  the phone's ten Telegram recordings, each mixed to one and told apart in
  the app's 5-minute chunks and again in 60 s chunks (no 20-30 min call is on
  the phone, so the linking is measured by cutting short calls finer);
  truth = 20 ms frames where one channel leads by 10 dB, as in R16.

  | Call | Length | Truth | 5-min chunks | 60 s chunks |
  |---|---|---|---|---|
  | 14:46, the one real conversation | 217 s | 105 s | named; 98.6% right, 92.6% of it covered; cosine 0.13 | the same: 98.6%, 92.8%; 0.10 |
  | 16:29, one speaker | 306 s | 170 s | one voice: not named | two "voices" at 0.96: not named, but the linking was wrong (fixed below) |
  | 10:06, one speaker | 113 s | 12 s | one voice: not named | 21.5 s and 1.7 s at 0.62: not named |
  | 14:21, test call | 153 s | 37 s | 93 s and 5 s at 0.63: not named | not named |
  | 14:13, test call | 167 s | 35 s | named: 7.6 s and 65.9 s at 0.11; 69.4% right | 0.77: not named |
  | 10:08, 15:08, 14:21 (30th), 10:50 | 6-65 s | under 16 s | not named | not named |

  So SC-001's 95% holds on the only call that can score it, and the
  chunking does not change it. Every one-speaker call stayed unnamed. 14:13
  was named and only 69% matches its channels; R16 already found its
  channels an unsure truth (a test call, played audio and speakerphone), so
  it is not counted for or against, but it is the case to listen to.
- **Fixed after it**: sherpa-onnx is told two voices, so a chunk of one
  speaker comes back as two of theirs; in 60 s chunks the 306 s call had a
  "second" call voice at cosine 0.96 to the first. VoiceLink now joins a
  chunk's two voices into one call voice when they are 0.6 or more alike.
  Run again on the three calls it touches: 16:29 in 60 s chunks one voice
  (283.5 s), as in 5-minute chunks; 10:06 one voice in both; 14:13 now named
  in both chunkings alike (cosine 0.11 and 0.19), 69% and 63% right against
  its unsure channels.

## V5. Labelling the lines and cutting at a turn

- **Decision (as built)**: each line takes the voice that covers most of its
  time. When the other voice covers at least 1 s of it, each word goes to the
  voice speaking when it starts, a run of words under 1 s joins its
  neighbour, and the line is cut between the runs (`VoiceSeam`, plain
  Kotlin), each part keeping its own words. The word times come from the
  JNI's `segmentWords` (kept from CallVault, unused until now), read only for
  one-mix passes and kept in memory with each line. They are in the call's
  time: whisper.cpp v1.9.3's `whisper_full_get_token_t0` maps a token back
  from the VAD's compressed timeline, so the bridge now reads that instead of
  the raw `token_data.t0` (compressed), and no mapping of our own is needed.
- **Rationale**: R16's 11:44 call had lines merged across a turn come out
  45/55; CallVault's `SpeakerSeamSplit` solved the same.
- **One voice, or too little**: fewer than 10 s of speech in all, or one
  voice holding over 95% of the speech, or the two voices' embeddings closer
  than 0.6 cosine (one person heard twice): no names (FR-004). **Measure**
  the 0.6 on calls with one speaker (a voice menu, a voicemail) and with two.

## V6. Which voice is the owner's

- **Decision**: the owner's print is an L2-normalised mean embedding of the
  owner's speech. A voice is You when its cosine with the print is at least
  **0.45** and at least **0.20** above the other voice's; the other is Them.
  Otherwise Voice 1 / Voice 2, Voice 1 the one heard first.
- **Rationale**: R16: the user 0.64 and 0.53 on two calls, the voice menu
  0.17, two strangers 0.27 each. 0.45 sits between 0.27 and 0.53; the margin
  stops a near tie. Wrong names are worse than none.
- **Measure**: the margins on at least ten of the user's own one-mix calls
  and five calls between other people before release; move them only with
  the numbers written into this section.

## V7. Where the print comes from (decided 2026-11-03)

- **Recorded sample first**: Settings > Transcripts > Your voice > Record:
  the user reads a short text shown on screen in their language, 30 s, from
  the microphone. Needs `RECORD_AUDIO`, asked only at that tap. The audio is
  kept in memory only, embedded, and dropped; no file.
- **From messenger calls, until a sample is recorded**: after a SPLIT call is
  transcribed, the left side's speech is used when all hold: at least 20 s of
  it after `QuietSide` and `LineCleanup`; no speakerphone (the app knows the
  route during the call - **check** that it is recorded; if not, the
  left-right level correlation stays under 0.3); and the left side's own
  windows agree with each other (cosine 0.7 or more) and differ from the
  right side's (under 0.4). A print from calls is the mean of up to five such
  calls, newest kept. R16's test calls whose left side carried played audio
  or echo would fail the agreement or the difference test; **measure** that
  on those recordings.
- A recorded sample replaces a print from calls; deleting the print stops
  nothing else. The switch for "learn from my calls" is on by default when no
  sample is recorded, and the manual says what it does.

## V8. Storage of the print

- **Decision**: a small file under `noBackupFilesDir`: the vector (192
  floats for TitaNet), the model id it belongs to, where it came from,
  when, and for calls, how many. Not in the transcripts database and not in
  backups (`data_extraction_rules` already excludes no-backup). A change of
  embedding model makes the print void: it is remade from the sample (gone)
  or the calls, and Settings asks for a new recording.
- **Rationale**: a print is biometric data under GDPR; kept apart so it is
  deleted in one place and never leaves with a backup.

## V9. Labels in the database

- **Decision**: `speaker` in `transcript_segments` takes two new keys,
  `voice1` and `voice2`. A transcript gets `voiceSource` (`none`,
  `channels`, `voices`, `print`, `marked`). Marking a voice as yours
  rewrites the lines' keys and sets `marked`; undoing it puts back
  `voice1` / `voice2` and `voices`. A database
  migration (version 1 to 2) adds the transcript column; old rows read as
  `channels` if any line is `you`/`them`, else `none`.
- **Rationale**: lines are the unit search, copy and share use; keeping
  labels there means FR-014 needs no change in them.

## V10. The summary and the CRM

- **Summary**: `SummaryRunner` names You and Them as today; for
  Voice 1 / Voice 2 it writes "Speaker 1" / "Speaker 2" and the prompt says
  the user is one of them, unknown which.
- **CRM** (`crm/ContentText`): You Seller, Them Customer (as now), Voice 1
  and 2 Speaker 1 and 2. Content is sent once (002); a later mark does not
  resend.
- **Order**: the voice step runs before the summary (V3), so a work call's
  content goes out with the names it will keep, unless the user marks a voice
  later.

## V11. Size of the APK

- **Measured 2026-11-03 (T007)**, release build: the APK is 22.8 MB with
  the voice engine. In it `libonnxruntime.so` is 22.2 MB (7.96 MB
  compressed) and `libvoicejr.so` - our bridge with sherpa-onnx's code
  linked in statically - 4.4 MB (1.67 MB compressed): +9.6 MB to download,
  so about 13.2 MB without them. Installed, the libraries are unpacked
  (`useLegacyPackaging`), +26.7 MB on the phone. The models (about 42 MB)
  are downloads.
