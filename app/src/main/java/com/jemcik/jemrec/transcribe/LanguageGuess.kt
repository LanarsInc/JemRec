package com.jemcik.jemrec.transcribe

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The language of a call, found before it is transcribed, when the user has
 * left it to "detect automatically".
 *
 * Why before: the model is chosen by language (NB-Whisper for Norwegian), and
 * whisper's own detection only runs inside a transcription, once a model is
 * already loaded. And why pinned afterwards: whisper left on "auto" detects
 * again for every five-minute pass, and on a Hebrew call it writes Latin
 * transliteration (CallVault's measurement) where the same call pinned to
 * Hebrew comes out in Hebrew.
 *
 * Each side of a call whose sides are apart is guessed on its own: the two
 * people may not speak the same language.
 *
 * Plain, for the tests; the engine runs whisper on [speech] and hands the
 * answer to [parse] and [decide].
 */
object LanguageGuess {

    /**
     * How much of the call is read for speech: a minute, which a ringing tone
     * and a greeting do not fill. Decoding is the slow part (TranscriptionEngine.detectLanguages).
     */
    const val LOOK_MS = 60_000L

    /** Whisper reads 30 s at a time; more would be cut. */
    private const val SAMPLE_SECONDS = 30

    /** Less speech than this is not enough to tell a language by. */
    private const val MIN_SECONDS = 3

    /** A second this far below the loudest is not speech. */
    private const val SPEECH_RANGE_DB = 25.0

    /** And never quieter than this, whatever the loudest was: line hiss is not speech. */
    private const val FLOOR_DBFS = -50.0

    /** Below this, whisper is guessing; the call is left to its detection pass by pass. */
    const val MIN_PROBABILITY = 0.5

    /**
     * The seconds of [audio] that carry speech, in order, up to 30 s: the
     * louder seconds of the first [LOOK_MS], so a ringing tone, a silent
     * start or a long pause does not use up whisper's one window. Null when
     * there are fewer than [MIN_SECONDS] of them.
     */
    fun speech(audio: FloatArray, rate: Int = AudioDecoder.TARGET_SAMPLE_RATE): FloatArray? {
        val seconds = audio.size / rate
        if (seconds < MIN_SECONDS) return null
        val db = DoubleArray(seconds) { s ->
            var sum = 0.0
            for (i in s * rate until (s + 1) * rate) sum += audio[i] * audio[i]
            val rms = sqrt(sum / rate)
            if (rms <= 0.0) -120.0 else 20 * log10(rms)
        }
        val bar = maxOf(db.max() - SPEECH_RANGE_DB, FLOOR_DBFS)
        val kept = db.indices.filter { db[it] >= bar }.take(SAMPLE_SECONDS)
        if (kept.size < MIN_SECONDS) return null
        val out = FloatArray(kept.size * rate)
        kept.forEachIndexed { k, s -> System.arraycopy(audio, s * rate, out, k * rate, rate) }
        return out
    }

    /** Whisper's answer, "no:0.812 da:0.101 sv:0.050", as codes with their probabilities, likeliest first. */
    fun parse(answer: String): List<Pair<String, Double>> =
        answer.split(' ').mapNotNull { item ->
            val code = item.substringBefore(':', "")
            val p = item.substringAfter(':', "").toDoubleOrNull()
            if (code.isEmpty() || p == null) null else code to p
        }

    /**
     * The language to pin, or null to leave it to whisper: the likeliest, if
     * it is one JemRec offers and whisper is sure enough of it. Nynorsk is
     * Norwegian here, as it is in Settings.
     */
    fun decide(guesses: List<Pair<String, Double>>): String? {
        val (code, p) = guesses.firstOrNull() ?: return null
        val language = if (code == "nn") "no" else code
        return language.takeIf { p >= MIN_PROBABILITY && it in TranscriptionLanguageChoice.SUPPORTED }
    }

    /**
     * A side [decide] could not tell, given the language the call's [other]
     * side was told in: that language only when this side had no speech to
     * tell by; null otherwise, for whisper to detect as it goes.
     *
     * Not when the other side's language is merely among the guesses:
     * measured on the Galaxy A24, 2026-10-30, a side of English news under a
     * Russian speaker came back "es 0.28, pt 0.26, ru 0.24" from
     * large-v3-turbo, and taking Russian there would write English as Russian.
     */
    fun follow(guesses: List<Pair<String, Double>>, other: String?): String? =
        other.takeIf { guesses.isEmpty() }
}
