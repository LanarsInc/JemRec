package com.jemcik.jemrec.transcribe

/**
 * Which of a one-mix call's two voices is the phone's owner, from a print of
 * their voice (specs/003-voice-labels, research.md V6).
 *
 * A voice is You only when it matches the print by [MIN_MATCH] and the other
 * voice by [MARGIN] less; then the other is Them. Otherwise both stay Voice 1
 * and Voice 2: a wrong You is worse than none, as it puts the owner's name on
 * the customer's words.
 *
 * The numbers are R16's (Galaxy A24, 2026-10-30), cosines of a print from
 * the owner's own calls against each voice: the owner 0.64 and 0.53 on two
 * calls, a voice menu 0.17, two strangers 0.27 each. [MIN_MATCH] sits between
 * the strangers and the owner's lower call; [MARGIN] keeps a near tie
 * unnamed. To be measured again on the owner's calls before they are final
 * (T043).
 *
 * Plain arithmetic, tested off the phone.
 */
object VoiceNames {

    const val MIN_MATCH = 0.45

    const val MARGIN = 0.20

    /**
     * Names for voice 0 and voice 1 of a call whose [embeddings] are its two
     * voices', given the owner's [print]; Voice 1 and Voice 2 when it does not
     * decide.
     */
    fun decide(embeddings: List<FloatArray>, print: FloatArray?): Pair<Speaker, Speaker> {
        if (print == null || embeddings.size != 2) return Speaker.VOICE1 to Speaker.VOICE2
        val first = VoiceLink.cosine(embeddings[0], print)
        val second = VoiceLink.cosine(embeddings[1], print)
        return when {
            first >= MIN_MATCH && first - second >= MARGIN -> Speaker.YOU to Speaker.THEM
            second >= MIN_MATCH && second - first >= MARGIN -> Speaker.THEM to Speaker.YOU
            else -> Speaker.VOICE1 to Speaker.VOICE2
        }
    }
}
