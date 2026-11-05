package com.jemcik.jemrec.transcribe

import com.jemcik.jemrec.transcribe.SpeechGapSnap.Speech

/**
 * Speech the VAD heard that whisper wrote no line for: where to listen again.
 *
 * whisper can leave a stretch of speech out altogether. LANARS's stand, 2026-11-05, a 274 s call
 * on the Galaxy A24 pinned to English: the answerer's one Russian sentence (0:46-1:10) had no
 * line, and neither had 18 s of English answer at 2:52-3:10 - the voices' turns had both, the
 * transcript neither. Such a stretch is transcribed again on its own, its language found on its
 * own, so a sentence in another language comes out in its own (TranscriptionEngine).
 *
 * Plain arithmetic, in one pass's own timeline, tested off the phone.
 */
object GapFill {

    /** Less unwritten speech than this is a breath or a word whisper merged into a line, not a gap. */
    const val MIN_GAP_MS = 3_000L

    /** Stretches this close together are one: the VAD splits a sentence at its pauses. */
    const val JOIN_MS = 600L

    /**
     * Gaps this close, with no line between them, are heard as one: each costs a language
     * detection (about 23 s on the Galaxy A24), and whisper reads a short pause as one.
     */
    const val JOIN_GAPS_MS = 2_500L

    /** Heard a little wider than the VAD's edges, so the first and last words are whole. */
    const val PAD_MS = 300L

    /**
     * A line with word times covers only this long after each word (up to the next): the same
     * call had a 30 s line of 6 words at 1:10-1:40 over speech the voices heard throughout.
     */
    const val WORD_COVERS_MS = 1_500L

    /**
     * A line with fewer words than one in this long, over [SPARSE_MIN_MS] or more, is not trusted:
     * the same call's 1:10-1:40 line had 6 English words over 30 s of Russian - "you answer",
     * "English" - left in the transcript beside the Russian heard again around them. Such a line
     * covers nothing, and goes when its stretch is heard again.
     */
    const val SPARSE_MS_PER_WORD = 2_500L
    const val SPARSE_MIN_MS = 5_000L

    /** At most this many gaps a pass, the longest: each costs a language detection and a decode. */
    const val MAX_GAPS = 6

    /**
     * The stretches of [speech] that no line of [lines] covers, each at least [MIN_GAP_MS],
     * widened by [PAD_MS] within [lengthMs], longest first and at most [MAX_GAPS], then in order.
     * Empty when the VAD was off (no stretches): then nothing is known to be missing.
     */
    fun gaps(speech: List<Speech>, lines: List<TranscriptSegment>, lengthMs: Long): List<LongRange> {
        if (speech.isEmpty()) return emptyList()
        val joined = mutableListOf<Speech>()
        for (s in speech.sortedBy { it.startMs }) {
            val last = joined.lastOrNull()
            if (last != null && s.startMs - last.endMs <= JOIN_MS) joined[joined.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs))
            else joined += s
        }
        // Only what will stay: a loop whisper repeated or a note of a sound is taken out later
        // (LineCleanup), and the same call's 2:52-3:10 was such a loop - covered, to this, and empty
        // in the transcript.
        val covered = LineCleanup.withoutLoops(lines.filterNot { LineCleanup.isNote(it.text) })
            .filterNot(::sparse).flatMap(::coverage).sortedBy { it.first }
        val pieces = joined.flatMap { s -> uncovered(s.startMs, s.endMs, covered) }
        val together = mutableListOf<LongRange>()
        for (piece in pieces) {
            val last = together.lastOrNull()
            val lineBetween = last != null && covered.any { it.first in last.last..piece.first }
            if (last != null && piece.first - last.last <= JOIN_GAPS_MS && !lineBetween) together[together.lastIndex] = last.first..piece.last
            else together += piece
        }
        val open = together.filter { it.last - it.first >= MIN_GAP_MS }
        return open.sortedByDescending { it.last - it.first }.take(MAX_GAPS)
            .map { maxOf(0L, it.first - PAD_MS)..minOf(lengthMs, it.last + PAD_MS) }
            .sortedBy { it.first }
    }

    /** A line too long for its words to be what was said (see [SPARSE_MS_PER_WORD]). */
    internal fun sparse(line: TranscriptSegment): Boolean {
        val span = line.endMs - line.startMs
        val words = line.text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
        return span >= SPARSE_MIN_MS && words * SPARSE_MS_PER_WORD < span
    }

    /** What [line] says it covers: its span, or with word times, the time near its words. */
    internal fun coverage(line: TranscriptSegment): List<LongRange> {
        if (line.words.isEmpty()) return listOf(line.startMs..line.endMs)
        val starts = line.words.map { it.startMs.coerceIn(line.startMs, line.endMs) }
        return starts.mapIndexed { i, at ->
            val next = starts.getOrNull(i + 1) ?: line.endMs
            at..minOf(next, at + WORD_COVERS_MS).coerceAtLeast(at)
        }
    }

    /** The parts of [from]..[to] outside every range of [covered] (sorted by start). */
    private fun uncovered(from: Long, to: Long, covered: List<LongRange>): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var at = from
        for (c in covered) {
            if (c.last <= at) continue
            if (c.first >= to) break
            if (c.first > at) out += at..c.first
            at = maxOf(at, c.last)
        }
        if (at < to) out += at..to
        return out
    }

    /** How many times a pass's gaps are looked for: once, and once more for what is still unwritten. */
    const val ROUNDS = 2

    /** [gaps] less those already heard: within half a second at both ends of one in [done]. */
    fun again(gaps: List<LongRange>, done: List<LongRange>): List<LongRange> =
        gaps.filterNot { g -> done.any { d -> kotlin.math.abs(g.first - d.first) <= 500 && kotlin.math.abs(g.last - d.last) <= 500 } }

    /**
     * [found] - lines whisper wrote for a gap, in the gap's own time - moved to [gap]'s place in
     * the pass, kept only inside it (a padded edge must not repeat a neighbour's words), and
     * merged with [lines] in order.
     */
    fun merge(lines: List<TranscriptSegment>, gap: LongRange, found: List<TranscriptSegment>): List<TranscriptSegment> {
        val moved = found.map { s ->
            s.copy(
                startMs = s.startMs + gap.first,
                endMs = minOf(s.endMs + gap.first, gap.last),
                words = s.words.map { it.copy(startMs = it.startMs + gap.first) },
            )
        }.filter { it.text.isNotBlank() && it.startMs < gap.last && lines.none { l -> l.startMs <= it.startMs && it.endMs <= l.endMs && l.words.isEmpty() } }
        if (moved.isEmpty()) return lines
        // What was heard again is what was said there: a loop inside the gap goes, and a line
        // reaching into it keeps only its words outside it, so nothing is said twice (as VoiceSeam
        // rebuilds a cut line from its words).
        val kept = lines.flatMap { l ->
            when {
                l.endMs <= gap.first || l.startMs >= gap.last -> listOf(l)
                l.startMs >= gap.first && l.endMs <= gap.last -> emptyList()
                // Not trusted, and its stretch is heard again: none of it stays.
                sparse(l) -> emptyList()
                l.words.isEmpty() -> listOf(l)
                else -> {
                    // Its words before the gap and after it, each a line of its own, so the order holds.
                    val before = l.words.filter { it.startMs < gap.first }
                    val after = l.words.filter { it.startMs >= gap.last }
                    listOfNotNull(
                        before.takeIf { it.isNotEmpty() }?.let { w ->
                            l.copy(endMs = minOf(l.endMs, gap.first), text = w.joinToString(" ") { it.text }, words = w)
                        },
                        after.takeIf { it.isNotEmpty() }?.let { w ->
                            l.copy(startMs = w.first().startMs, text = w.joinToString(" ") { it.text }, words = w)
                        },
                    )
                }
            }
        }
        return (kept + moved).sortedBy { it.startMs }
    }
}
