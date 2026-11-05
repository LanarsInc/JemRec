package com.jemcik.jemrec.transcribe

/** A stretch of one voice, from the recording's start. [voice] is 0 or 1. */
data class VoiceTurn(val startMs: Long, val endMs: Long, val voice: Int) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * The voices of a long call, told apart chunk by chunk, as the call's two
 * (specs/003-voice-labels, research.md V4).
 *
 * A call is told apart in the 5-minute chunks it is transcribed in, so memory
 * does not grow with its length: a whole hour would be 230 MB of samples
 * before the models' own. Each chunk names its voices 0 and 1 on its own, so
 * the same person can be 0 in one chunk and 1 in the next. A chunk's voice
 * joins the call's voice its embedding is closest to (cosine; embeddings are
 * unit length), and the call's voice is the running mean of what joined it,
 * weighted by speech. Most calls are one chunk, and pass through unchanged
 * but for the numbering.
 *
 * At the end the voice heard first is 0 - "Voice 1" - so the names read in
 * the order the call does.
 *
 * Plain arithmetic, tested off the phone.
 */
object VoiceLink {

    /**
     * A chunk's lone voice is the call's second when its cosine with the
     * call's only voice so far is below this; otherwise the same person again.
     * The same line as [VoiceSilence.SAME_VOICE_COSINE]: one person is one
     * voice whether in one chunk or across two.
     */
    const val NEW_VOICE_BELOW = VoiceSilence.SAME_VOICE_COSINE

    /** One chunk told apart: its turns in call time, and each of its voices' embedding and speech. */
    data class Chunk(val turns: List<VoiceTurn>, val embeddings: Map<Int, FloatArray>)

    /** The call's voices: turns numbered 0 and 1, and each voice's embedding (index = voice). */
    data class Call(val turns: List<VoiceTurn>, val embeddings: List<FloatArray>) {
        fun speechMs(voice: Int): Long = turns.filter { it.voice == voice }.sumOf { it.lengthMs }
    }

    fun link(chunks: List<Chunk>): Call {
        val sums = mutableListOf<FloatArray>()
        val weights = mutableListOf<Double>()
        val turns = mutableListOf<VoiceTurn>()

        for (chunk in chunks) {
            val speech = chunk.embeddings.keys.associateWith { v -> chunk.turns.filter { it.voice == v }.sumOf { it.lengthMs } }
            val locals = chunk.embeddings.keys.filter { (speech[it] ?: 0L) > 0L }.sorted()
            if (locals.isEmpty()) continue
            val calls = sums.map(::unit)
            // sherpa-onnx is told two voices, so a chunk where one person spoke comes back as two of
            // theirs. Measured on the A24, 2026-11-03 (VoiceAccuracyTest): a 306 s call of one
            // speaker, cut into 60 s chunks, had its "two" call voices at cosine 0.96. Two voices of
            // one chunk that alike are one person, and join one call voice.
            val samePerson = locals.size == 2 &&
                cosine(chunk.embeddings.getValue(locals[0]), chunk.embeddings.getValue(locals[1])) >= NEW_VOICE_BELOW
            val map: Map<Int, Int> = when {
                samePerson -> {
                    val (a, b) = locals
                    val mean = FloatArray(chunk.embeddings.getValue(a).size) {
                        chunk.embeddings.getValue(a)[it] * speech.getValue(a) + chunk.embeddings.getValue(b)[it] * speech.getValue(b)
                    }
                    val call = when {
                        calls.isEmpty() -> 0
                        calls.size == 1 -> if (cosine(mean, calls[0]) >= NEW_VOICE_BELOW) 0 else 1
                        else -> if (cosine(mean, calls[0]) >= cosine(mean, calls[1])) 0 else 1
                    }
                    mapOf(a to call, b to call)
                }
                calls.isEmpty() -> locals.withIndex().associate { (i, v) -> v to i }
                locals.size == 2 && calls.size == 1 -> {
                    val (near, far) = locals.sortedByDescending { cosine(chunk.embeddings.getValue(it), calls[0]) }
                    mapOf(near to 0, far to 1)
                }
                locals.size == 2 -> {
                    val (a, b) = locals
                    val straight = cosine(chunk.embeddings.getValue(a), calls[0]) + cosine(chunk.embeddings.getValue(b), calls[1])
                    val crossed = cosine(chunk.embeddings.getValue(a), calls[1]) + cosine(chunk.embeddings.getValue(b), calls[0])
                    if (straight >= crossed) mapOf(a to 0, b to 1) else mapOf(a to 1, b to 0)
                }
                calls.size == 1 -> {
                    val v = locals.single()
                    mapOf(v to if (cosine(chunk.embeddings.getValue(v), calls[0]) >= NEW_VOICE_BELOW) 0 else 1)
                }
                else -> {
                    val v = locals.single()
                    val e = chunk.embeddings.getValue(v)
                    mapOf(v to if (cosine(e, calls[0]) >= cosine(e, calls[1])) 0 else 1)
                }
            }
            for ((local, call) in map) {
                val w = speech.getValue(local).toDouble()
                val e = chunk.embeddings.getValue(local)
                if (call == sums.size) {
                    sums += FloatArray(e.size) { (e[it] * w).toFloat() }
                    weights += w
                } else {
                    val s = sums[call]
                    for (i in s.indices) s[i] += (e[i] * w).toFloat()
                    weights[call] += w
                }
            }
            chunk.turns.filter { it.voice in map }.forEach { turns += it.copy(voice = map.getValue(it.voice)) }
        }

        val embeddings = sums.map(::unit)
        val sorted = turns.sortedBy { it.startMs }
        // The voice heard first is Voice 1.
        if (sorted.firstOrNull()?.voice == 1) {
            return Call(sorted.map { it.copy(voice = 1 - it.voice) }, embeddings.reversed())
        }
        return Call(sorted, embeddings)
    }

    /** Cosine of two vectors; 0 when either is all zeros. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / kotlin.math.sqrt(na * nb)
    }

    private fun unit(v: FloatArray): FloatArray {
        val n = kotlin.math.sqrt(v.sumOf { it.toDouble() * it })
        return if (n == 0.0) v.copyOf() else FloatArray(v.size) { (v[it] / n).toFloat() }
    }
}
