package com.jemcik.jemrec.transcribe

/**
 * What whisper writes that is not what anyone said, taken out of a merged
 * transcript. Each rule is from a call on the Galaxy A24, 2026-10-29:
 *
 * - **Echo.** On speakerphone the other person's voice comes out of the
 *   phone's speaker into its microphone, and JemRec records the microphone
 *   without the call app's echo cancellation (README, "Does it really record
 *   both sides?"). A Norwegian test call had their every sentence twice, once
 *   clean on their side and once, worse heard, on yours. A line of yours said
 *   while theirs is said, mostly in their words, is their voice: it goes.
 * - **Loops.** On quiet or unclear sound whisper repeats itself: an English
 *   test call had "I'm going to the pool." six times and "I was just
 *   wondering if you were going to say something." five, each a line of its
 *   own, from one side. A line the same as the one before it from the same
 *   side goes.
 * - **Notes.** "[inaudible]", "(music)": whisper's descriptions of sound,
 *   not words said. They go.
 *
 * Lines are only ever dropped, never rewritten.
 */
object LineCleanup {

    /** A line of yours is an echo when at least this share of its words are in their lines said at the time. */
    const val ECHO_SHARE = 0.6

    /** Fewer words than this cannot be told from a reply that repeats theirs ("Friday?" - "Friday."). */
    const val ECHO_MIN_WORDS = 4

    fun apply(lines: List<TranscriptSegment>): List<TranscriptSegment> =
        withoutLoops(withoutEcho(lines.filterNot { isNote(it.text) }))

    internal fun isNote(text: String): Boolean = NOTE.matches(text.trim())

    internal fun withoutEcho(lines: List<TranscriptSegment>): List<TranscriptSegment> {
        val theirs = lines.filter { it.speaker == Speaker.THEM }
        if (theirs.isEmpty()) return lines
        return lines.filterNot { line ->
            if (line.speaker != Speaker.YOU) return@filterNot false
            val words = words(line.text)
            if (words.size < ECHO_MIN_WORDS) return@filterNot false
            val heard = theirs.filter { it.startMs < line.endMs && it.endMs > line.startMs }
                .flatMap { words(it.text) }.toSet()
            words.count { it in heard }.toDouble() / words.size >= ECHO_SHARE
        }
    }

    internal fun withoutLoops(lines: List<TranscriptSegment>): List<TranscriptSegment> {
        val last = mutableMapOf<Speaker, String>()
        return lines.filter { line ->
            val said = words(line.text).joinToString(" ")
            val again = said.isNotEmpty() && last[line.speaker] == said
            last[line.speaker] = said
            !again
        }
    }

    private fun words(text: String) = WORD.findAll(text.lowercase()).map { it.value }.toList()

    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val NOTE = Regex("""^[\[(*♪].*[\])*♪]$""")
}
