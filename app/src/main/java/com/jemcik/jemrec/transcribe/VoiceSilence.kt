package com.jemcik.jemrec.transcribe

/**
 * Whether a one-mix call's voices may be named at all (spec FR-004,
 * research.md V5). A wrong split is worse than none: a voice menu, a
 * voicemail, or one person on a bad line split into "two people" would put
 * half of one person's words under someone else.
 *
 * Plain arithmetic, tested off the phone.
 */
object VoiceSilence {

    /** Under this much speech in all, too little to tell two voices apart. */
    const val MIN_SPEECH_MS = 10_000L

    /** One voice holding more than this share of the speech is one person, the rest noise. */
    const val MAX_ONE_VOICE_SHARE = 0.95

    /**
     * Two voices at least this alike (cosine of their embeddings) are one
     * person heard twice. R16 (A24, 2026-10-30): the owner's own voice
     * against a print of it 0.53-0.64, two different people 0.17-0.27;
     * between those, to be measured on calls with one speaker (T028).
     */
    const val SAME_VOICE_COSINE = 0.6

    fun twoVoices(call: VoiceLink.Call): Boolean {
        if (call.embeddings.size < 2) return false
        val first = call.speechMs(0)
        val second = call.speechMs(1)
        val total = first + second
        if (total < MIN_SPEECH_MS) return false
        if (maxOf(first, second).toDouble() / total > MAX_ONE_VOICE_SHARE) return false
        return VoiceLink.cosine(call.embeddings[0], call.embeddings[1]) < SAME_VOICE_COSINE
    }
}
