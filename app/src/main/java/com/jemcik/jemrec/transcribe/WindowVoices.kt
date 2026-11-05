package com.jemcik.jemrec.transcribe

/**
 * The two voices of a chunk the engine left as one, told apart by short windows of its speech
 * (specs/003-voice-labels, research.md V4).
 *
 * sherpa-onnx is told there are two voices and cuts where its pieces are least alike. On the A24
 * (2026-11-05, release build) a 235 s call of two people, no speakerphone, came back 204.6 s and
 * 0.4 s: the cut fell at a scrap of noise (the least alike pair of 3 s windows, cosine 0.01), and
 * both people stayed one voice. Its speech in 3 s windows fell into two groups, 38 and 30 windows,
 * within 0.83 and 0.81, between 0.47; the owner's print 0.77 to one and 0.24 to the other.
 *
 * So when the engine leaves one voice ([needed]), the speech is cut into [WINDOW_MS] windows every
 * [HOP_MS] within each of its turns, every window is described, and the windows are split in two
 * ([split]); each stretch of speech goes to its window's group ([turns]). Whether the two are
 * really two people is not decided here: VoiceLink joins two groups 0.6 or more alike into one
 * person, and VoiceSilence then names nothing, as for any chunk.
 *
 * Plain arithmetic, tested off the phone; VoiceStep does the describing.
 */
object WindowVoices {

    /** Long enough for the embedding to say who it is, short enough to hold one person most of the time. */
    const val WINDOW_MS = 2_000L

    const val HOP_MS = 1_000L

    /** A turn shorter than this has no window of its own: it takes the group of the window nearest it. */
    const val MIN_TURN_MS = 1_000L

    /**
     * Whether the engine left [turns] with one voice: fewer than two voices described, or one
     * holding more than VoiceSilence's share of the speech.
     */
    fun needed(turns: List<VoiceTurn>, described: Set<Int>): Boolean {
        if (turns.isEmpty()) return false
        if (described.size < 2) return true
        val byVoice = turns.groupBy { it.voice }.mapValues { (_, t) -> t.sumOf { it.lengthMs } }
        val total = byVoice.values.sum()
        return total > 0 && byVoice.values.max().toDouble() / total > VoiceSilence.MAX_ONE_VOICE_SHARE
    }

    /**
     * The windows of [turns] (any voice), in order: every [HOP_MS] within each turn of at least
     * [MIN_TURN_MS], the last one ending at the turn's end; a turn shorter than [WINDOW_MS] is one
     * window of its own length.
     */
    fun windows(turns: List<VoiceTurn>): List<LongRange> =
        speech(turns).flatMap { t ->
            when {
                t.lengthMs < MIN_TURN_MS -> emptyList()
                t.lengthMs <= WINDOW_MS -> listOf(t.startMs until t.endMs)
                else -> {
                    val starts = generateSequence(t.startMs) { it + HOP_MS }.takeWhile { it + WINDOW_MS < t.endMs }.toMutableList()
                    starts += t.endMs - WINDOW_MS
                    starts.map { it until it + WINDOW_MS }
                }
            }
        }

    /**
     * [vectors] in two groups, 0 and 1: 2-means on cosine, started from the two least alike.
     * Null with fewer than two.
     */
    fun split(vectors: List<FloatArray>): IntArray? {
        if (vectors.size < 2) return null
        var a = 0
        var b = 1
        var least = Double.MAX_VALUE
        for (i in vectors.indices) for (j in i + 1 until vectors.size) {
            val c = VoiceLink.cosine(vectors[i], vectors[j])
            if (c < least) { least = c; a = i; b = j }
        }
        var centres = listOf(vectors[a], vectors[b])
        var group = IntArray(vectors.size)
        repeat(MAX_ROUNDS) {
            val next = IntArray(vectors.size) { i ->
                if (VoiceLink.cosine(vectors[i], centres[0]) >= VoiceLink.cosine(vectors[i], centres[1])) 0 else 1
            }
            if (it > 0 && next.contentEquals(group)) return group
            group = next
            centres = (0..1).map { g ->
                val members = vectors.filterIndexed { i, _ -> group[i] == g }
                if (members.isEmpty()) centres[g] else FloatArray(members[0].size) { k -> members.sumOf { m -> m[k].toDouble() }.toFloat() }
            }
        }
        return group
    }

    /**
     * The speech of [turns] as voices 0 and 1, from [windows]' [groups]: within a turn, each window
     * holds from halfway to the window before to halfway to the one after (the turn's own ends at
     * either side). A turn with no window takes the group of the window nearest it. Neighbours of
     * one voice are joined.
     */
    fun turns(turns: List<VoiceTurn>, windows: List<LongRange>, groups: IntArray): List<VoiceTurn> {
        require(windows.size == groups.size)
        if (windows.isEmpty()) return emptyList()
        val out = mutableListOf<VoiceTurn>()
        for (t in speech(turns)) {
            val inside = windows.indices.filter { windows[it].first >= t.startMs && windows[it].last < t.endMs }
            if (inside.isEmpty()) {
                val middle = (t.startMs + t.endMs) / 2
                val nearest = windows.indices.minBy { i -> minOf(Math.abs(windows[i].first - middle), Math.abs(windows[i].last + 1 - middle)) }
                out += VoiceTurn(t.startMs, t.endMs, groups[nearest])
                continue
            }
            for ((k, i) in inside.withIndex()) {
                val centre = (windows[i].first + windows[i].last + 1) / 2
                val from = if (k == 0) t.startMs else (centre + (windows[inside[k - 1]].first + windows[inside[k - 1]].last + 1) / 2) / 2
                val to = if (k == inside.lastIndex) t.endMs else (centre + (windows[inside[k + 1]].first + windows[inside[k + 1]].last + 1) / 2) / 2
                if (to > from) out += VoiceTurn(from, to, groups[i])
            }
        }
        return out.sortedBy { it.startMs }.fold(mutableListOf()) { acc, t ->
            val last = acc.lastOrNull()
            if (last != null && last.voice == t.voice && t.startMs <= last.endMs) acc[acc.lastIndex] = last.copy(endMs = maxOf(last.endMs, t.endMs))
            else acc += t
            acc
        }
    }

    /** [turns] of any voice as one voice's speech: sorted, overlaps joined. */
    private fun speech(turns: List<VoiceTurn>): List<VoiceTurn> =
        turns.sortedBy { it.startMs }.fold(mutableListOf()) { acc, t ->
            val last = acc.lastOrNull()
            if (last != null && t.startMs <= last.endMs) acc[acc.lastIndex] = last.copy(endMs = maxOf(last.endMs, t.endMs))
            else acc += t.copy(voice = 0)
            acc
        }

    private const val MAX_ROUNDS = 20
}
