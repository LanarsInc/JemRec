package com.jemcik.jemrec.transcribe

/**
 * A one-mix call's lines under its voices, a line two voices share cut where
 * the voice changes (spec FR-003, research.md V5).
 *
 * whisper writes a line by what it heard, not by who: across a quick turn one
 * line can hold both people. R16's 11:44 call (two voices, A24, 2026-10-30)
 * had such lines come out 45/55. So a line takes the voice that covers most
 * of it, unless the other covers at least [MIN_OTHER_MS] of it: then each
 * word goes to the voice speaking when it starts, and the line is cut between
 * the runs. A run shorter than [MIN_PART_MS] joins the one before it (or
 * after, at the start) - a word or two is more likely the turn's timing than
 * the other person - so a cut needs a real stretch of each. CallVault's
 * SpeakerSeamSplit did the same over two channels.
 *
 * Nothing is lost or said twice: the parts hold the line's words once each,
 * in order. A line with no word times (none were read) is never cut.
 *
 * Plain arithmetic, tested off the phone.
 */
object VoiceSeam {

    /** The other voice must cover this much of a line before it is cut. */
    const val MIN_OTHER_MS = 1_000L

    /** A run of words shorter than this is not a turn of its own. */
    const val MIN_PART_MS = 1_000L

    /**
     * [lines] labelled by [turns] (sorted, in call time), voice 0 as [first]
     * and voice 1 as [second]. With no turns, the lines come back as they are.
     */
    fun apply(lines: List<TranscriptSegment>, turns: List<VoiceTurn>, first: Speaker, second: Speaker): List<TranscriptSegment> {
        if (turns.isEmpty()) return lines
        fun name(voice: Int) = if (voice == 0) first else second
        return lines.flatMap { line ->
            val covered = IntArray(2)
            for (t in turns) {
                val overlap = minOf(t.endMs, line.endMs) - maxOf(t.startMs, line.startMs)
                if (overlap > 0) covered[t.voice] += overlap.toInt()
            }
            val main = when {
                covered[0] == 0 && covered[1] == 0 -> voiceAt((line.startMs + line.endMs) / 2, turns)
                covered[1] > covered[0] -> 1
                else -> 0
            }
            val other = 1 - main
            if (covered[other] < MIN_OTHER_MS || line.words.size < 2) {
                return@flatMap listOf(line.copy(speaker = name(main)))
            }
            val runs = runs(line, turns)
            if (runs.size < 2) listOf(line.copy(speaker = name(main)))
            else runs.mapIndexed { i, run ->
                TranscriptSegment(
                    startMs = if (i == 0) line.startMs else run.words.first().startMs,
                    endMs = if (i == runs.lastIndex) line.endMs else runs[i + 1].words.first().startMs,
                    text = run.words.joinToString(" ") { it.text },
                    speaker = name(run.voice),
                    words = run.words,
                )
            }
        }
    }

    private class Run(val voice: Int, val words: MutableList<Word>)

    /** The line's words in runs of one voice, short runs folded into their neighbours. */
    private fun runs(line: TranscriptSegment, turns: List<VoiceTurn>): List<Run> {
        val runs = mutableListOf<Run>()
        for (w in line.words) {
            val voice = voiceAt(w.startMs, turns)
            if (runs.lastOrNull()?.voice == voice) runs.last().words += w else runs += Run(voice, mutableListOf(w))
        }
        while (runs.size > 1) {
            val short = runs.indices.firstOrNull { lengthOf(runs, it, line.endMs) < MIN_PART_MS } ?: break
            val into = if (short == 0) 1 else short - 1
            if (into < short) runs[into].words += runs[short].words else runs[into].words.addAll(0, runs[short].words)
            runs.removeAt(short)
            // Two runs of one voice now side by side are one.
            var i = 1
            while (i < runs.size) {
                if (runs[i].voice == runs[i - 1].voice) { runs[i - 1].words += runs[i].words; runs.removeAt(i) } else i++
            }
        }
        return runs
    }

    private fun lengthOf(runs: List<Run>, i: Int, lineEndMs: Long): Long {
        val end = if (i == runs.lastIndex) lineEndMs else runs[i + 1].words.first().startMs
        return end - runs[i].words.first().startMs
    }

    /** The voice speaking at [ms]: the turn that holds it, else the nearest one (the earlier on a tie). */
    internal fun voiceAt(ms: Long, turns: List<VoiceTurn>): Int {
        turns.firstOrNull { ms >= it.startMs && ms < it.endMs }?.let { return it.voice }
        return turns.minBy { t -> if (ms < t.startMs) t.startMs - ms else ms - t.endMs }.voice
    }
}
