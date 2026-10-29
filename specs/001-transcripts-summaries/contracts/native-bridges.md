# Contract: native bridges

Both are CallVault's at `5cfa2c60`, with JNI symbols renamed. The Kotlin
declarations and the C++ change together; parameter lists are whatever the
CallVault source takes.

## `com.jemcik.jemrec.transcribe.WhisperNative` → `libwhisperjr.so`

From `whispercv.cpp` (`Java_com_baba_callvault_transcription_` →
`Java_com_jemcik_jemrec_transcribe_`).

| Call | Contract |
|---|---|
| `systemInfo(libDir)` | CPU features ggml sees; logged once per run |
| `initContext(modelPath, libDir): Long` | 0 on failure; only for a verified file |
| `freeContext(ptr)` | always, in `finally` |
| `transcribe(ptr, audio, threads, language, prompt, …)` | 16 kHz mono float in [-1, 1]; `language` an ISO code or `"auto"`; never translates; blocks; one at a time |
| `vadSegmentCount / vadSegmentStartMs / vadSegmentEndMs` | speech spans the VAD found in the last run |
| `requestAbort()` | any thread; the run returns early |
| `progressPercent()` | 0-100 of the current run |
| `segmentCount / segmentStartMs / segmentEndMs / segmentText / segmentWords` | results of the last run, times relative to the audio given |

## `com.jemcik.jemrec.summary.LlamaNative` → `libllamajr.so`

From `llamacv.cpp` (`Java_com_baba_callvault_summary_` →
`Java_com_jemcik_jemrec_summary_`).

| Call | Contract |
|---|---|
| `systemInfo(libDir)` | as above |
| `initContext(modelPath, libDir): Long` | 0 on failure; only for a verified file, and only after the memory check |
| `freeContext(ptr)` | always |
| `countTokens(ptr, text)` | for chunking against the context size |
| `generate(ptr, prompt, grammar, …): String` | output constrained by the GBNF grammar; blocks |
| `requestAbort()` | as above |
| `grammarAccepted(ptr, grammar)` | the grammar parses; checked in tests |

## Engine lock

`TranscriptionEngine` and `SummaryEngine` share one lock: at most one model is
in memory at a time.
