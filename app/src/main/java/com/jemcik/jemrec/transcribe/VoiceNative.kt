package com.jemcik.jemrec.transcribe

/**
 * Telling the two voices of a one-mix call apart, on the phone: sherpa-onnx
 * (pyannote segmentation-3.0 and a voice-embedding model) through our own
 * bridge, `voicejr.cpp` (specs/003-voice-labels, contracts/voice-native.md).
 *
 * Audio is 16 kHz mono float in [-1, 1], as whisper gets it. Measured in
 * R16 on a Galaxy A24 (2026-10-30): a 252 s call in 76-101 s, 349 MB peak.
 *
 * One handle is used from one thread at a time, under the heavy lock, except
 * [stop], which another thread calls to end a [turns] run at its next
 * embedding (voicejr.cpp says how).
 */
object VoiceNative {
    init { System.loadLibrary("voicejr") }

    /** Loads both models; 0 when either fails to load. Free with [close]. */
    external fun open(segmentationPath: String, embeddingPath: String, threads: Int): Long

    /**
     * The voices in [pcm], two at most: flat (startMs, endMs, voice) triples
     * sorted by start, voice 0 or 1, times from the buffer's start. Empty when
     * there is no speech or the run failed (the reason is in logcat).
     */
    external fun turns(ptr: Long, pcm: FloatArray): IntArray

    /**
     * One L2-normalised embedding of all of [pcm], so a dot product of two is
     * their cosine. Empty when the buffer is too short for the model. TitaNet
     * takes at most about 120 s at once (R16): callers give it 60 s or less.
     */
    external fun embed(ptr: Long, pcm: FloatArray): FloatArray

    /** True: [turns] on this handle ends early and returns empty, until called again with false. Any thread. */
    external fun stop(ptr: Long, stop: Boolean)

    /** Releases a handle from [open]. Safe to call with 0. */
    external fun close(ptr: Long)
}
