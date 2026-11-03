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
- **Check before release**: TitaNet-small's licence (NVIDIA publishes NeMo
  checkpoints under CC-BY-4.0, which needs attribution in NOTICE.md and the
  About screen). If it is not one the app can ship to users, CAM++
  (3D-Speaker, Apache-2.0, about 28 MB) replaces it: R16 found it as
  accurate; its speed on the A24 is measured before the switch.
- **Long calls**: TitaNet takes at most about 120 s at once (R16), so a
  voice's speech is embedded in windows of up to 60 s and averaged.

## V3. Where it runs in the transcription

- **Decision**: in `TranscriptionRunner.run`, after the lines are made and
  cleaned (`TurnMerge`, `LineCleanup`) and only when the call is MIX
  (`ChannelSides.decide`, unchanged). The lines are saved first, unnamed, as
  today; then the voice step runs under the same `EngineLock` / heavy queue,
  and the transcript's labels are updated in one write. A stop, a deleted
  recording, too little memory or a failure leaves the unnamed transcript.
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
  call's voice whose embedding it is closest to by cosine; the overlap's
  turns settle a tie. Most calls are one chunk.
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
- **Measure**: on a 20-30 min call (two Telegram calls mixed to one), the
  linking against the channels' truth; and the peak memory.

## V5. Labelling the lines and cutting at a turn

- **Decision**: each line takes the voice that covers most of its time.
  When the other voice covers at least 1 s of it, and a word boundary falls
  within 300 ms of that turn, the line is cut there (`VoiceSeam`, plain
  Kotlin), each part keeping its own words. The word times come from the
  JNI's `segmentWords` (kept from CallVault for this, unused so far), on the
  VAD's compressed timeline: mapped back to the call's time with the same
  speech stretches `SpeechGapSnap` uses.
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
