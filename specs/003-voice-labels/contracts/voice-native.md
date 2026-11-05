# Contract: the voice engine's bridge (`VoiceNative`, `voicejr.cpp`)

Kotlin side, `transcribe/VoiceNative.kt`; native side over sherpa-onnx's C
API. Audio is 16 kHz mono float in [-1, 1], as whisper gets it.

```kotlin
object VoiceNative {
    /** Loads both models; 0 on failure (a bad file was already refused by its sha256). */
    external fun open(segmentationPath: String, embeddingPath: String, threads: Int): Long

    /**
     * Two voices at most in [pcm]: turns as (startMs, endMs, voice) triples,
     * voice 0 or 1, and nothing else. Times from the buffer's start.
     */
    external fun turns(ptr: Long, pcm: FloatArray): IntArray

    /** One L2-normalised embedding of [pcm] (at most 60 s; longer is cut by the caller). */
    external fun embed(ptr: Long, pcm: FloatArray): FloatArray

    external fun close(ptr: Long)
}
```

- Clustering is told the number of voices (2); a third voice ends up in one
  of them (spec edge case). The clustering threshold is not used.
- Threads: `TranscriptionEngine.preferredThreadCount()`; R16 found 4 and 6
  the same on the A24.
- A failure returns an empty array; Kotlin treats it as "no names" (FR-004),
  and logs why.
- The handle is opened per run and closed in `finally`; the voice step holds
  the heavy lock throughout.
