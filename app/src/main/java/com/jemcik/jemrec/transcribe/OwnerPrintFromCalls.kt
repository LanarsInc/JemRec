package com.jemcik.jemrec.transcribe

/**
 * A print of the owner's voice from their own messenger calls, until they
 * record a sample (specs/003-voice-labels, research.md V7, decided
 * 2026-11-03).
 *
 * The left channel of a messenger call is the owner's microphone - but not
 * always the owner alone. R16 found test calls whose left side carried audio
 * played into the phone, and on speakerphone the other person's voice comes
 * back through the microphone too (ARCHITECTURE, "Speakerphone"); prints from
 * those sides were 0.2-0.46 to the owner's voice, one 0.85 to the other side.
 * So a call's left side is used only when all of these hold:
 * - at least [MIN_SPEECH_MS] of speech on it, after QuietSide and LineCleanup;
 * - not on speakerphone - or, where the route is not known, the two sides'
 *   20 ms levels correlate under [MAX_SIDE_CORRELATION] (an echo moves both);
 * - the left side agrees with itself: every two windows of it at least
 *   [MIN_AGREEMENT] alike (one person, not a person and a radio). Pairwise,
 *   not each to the mean: two unrelated windows are both 0.71 to theirs;
 * - and differs from the right: its mean under [MAX_LIKE_OTHER] to the right
 *   side's.
 *
 * The print is the mean of the last [KEEP_CALLS] such calls, so one odd call
 * does not decide it. The thresholds are first guesses from R16's numbers;
 * T043 measures them on the owner's calls.
 *
 * Plain arithmetic, tested off the phone.
 */
object OwnerPrintFromCalls {

    const val MIN_SPEECH_MS = 20_000L
    const val MAX_SIDE_CORRELATION = 0.3
    const val MIN_AGREEMENT = 0.7
    const val MAX_LIKE_OTHER = 0.4
    const val KEEP_CALLS = 5

    /** What one messenger call offers. */
    data class Call(
        /** The left side's speech, in ms. */
        val leftSpeechMs: Long,
        /** True on speakerphone, false not, null when not known. */
        val speakerphone: Boolean?,
        /** The two sides' 20 ms level correlation, over frames with sound. */
        val sideCorrelation: Double,
        /** Embeddings of windows of the left side's speech. */
        val leftWindows: List<FloatArray>,
        /** The right side's embedding, or null when it said too little. */
        val right: FloatArray?,
    )

    /** The left side's embedding if [call] may add to the print, else null. */
    fun offer(call: Call): FloatArray? {
        if (call.leftSpeechMs < MIN_SPEECH_MS) return null
        when (call.speakerphone) {
            true -> return null
            null -> if (call.sideCorrelation >= MAX_SIDE_CORRELATION) return null
            false -> Unit
        }
        if (call.leftWindows.size < 2) return null
        val w = call.leftWindows
        for (i in w.indices) for (j in i + 1 until w.size) if (VoiceLink.cosine(w[i], w[j]) < MIN_AGREEMENT) return null
        val left = mean(w)
        if (call.right != null && VoiceLink.cosine(left, call.right) >= MAX_LIKE_OTHER) return null
        return left
    }

    /** The calls kept after [offered] joins [kept]: the newest [KEEP_CALLS]. */
    fun keep(kept: List<FloatArray>, offered: FloatArray): List<FloatArray> = (kept + listOf(offered)).takeLast(KEEP_CALLS)

    /** The print from the calls kept: their unit-length mean. */
    fun print(kept: List<FloatArray>): FloatArray? = if (kept.isEmpty()) null else mean(kept)

    private fun mean(vectors: List<FloatArray>): FloatArray {
        val sum = FloatArray(vectors.first().size)
        for (v in vectors) for (i in sum.indices) sum[i] += v[i]
        val n = kotlin.math.sqrt(sum.sumOf { it.toDouble() * it })
        return if (n == 0.0) sum else FloatArray(sum.size) { (sum[it] / n).toFloat() }
    }
}
