package com.jemcik.jemrec.transcribe

/**
 * Lines whisper wrote for a side that was not speaking.
 *
 * Whisper writes words over near-silence - a known habit, worst with the
 * language left to detection. With the two sides transcribed apart, the
 * other side's channel says whether anyone was talking on this one: a line
 * whose own channel stayed quiet while the other was loud is that side's
 * microphone picking up nothing, not speech.
 *
 * A guard, not the fix for what was seen. On the Galaxy A24 (2026-10-29) a
 * Telegram test call's "Them" line came out as English words nobody said -
 * but that side was loud there (-9.4 dBFS against -10.0 on the other): it was
 * Russian speech heard as English with the language left to detection, and
 * with Russian pinned it came out right. The thresholds are strict so a real,
 * quiet speaker is never dropped; nothing in the test calls met them.
 *
 * Only for split calls; a mix has no other side to compare with.
 */
object QuietSide {

    /** A side at least this far below the other, for the whole line, was not the one speaking. */
    const val BELOW_DB = 20.0

    /** And quiet in itself: nothing on that channel above this. */
    const val QUIET_DB = -40.0

    /**
     * Whether [line] should go: its own side's loudest 20 ms frame across the
     * line is under [QUIET_DB] and [BELOW_DB] under the other side's loudest.
     * [left] and [right] are 20 ms levels for the whole call (ChannelSides.levels).
     */
    fun isPhantom(line: TranscriptSegment, left: DoubleArray, right: DoubleArray): Boolean {
        val (own, other) = when (line.speaker) {
            Speaker.YOU -> left to right
            Speaker.THEM -> right to left
            Speaker.NONE -> return false
        }
        val from = (line.startMs / ChannelSides.FRAME_MS).toInt().coerceAtLeast(0)
        val to = (line.endMs / ChannelSides.FRAME_MS).toInt().coerceAtMost(minOf(own.size, other.size) - 1)
        if (to < from) return false
        var ownPeak = -200.0
        var otherPeak = -200.0
        for (i in from..to) {
            if (own[i] > ownPeak) ownPeak = own[i]
            if (other[i] > otherPeak) otherPeak = other[i]
        }
        return ownPeak < QUIET_DB && otherPeak - ownPeak > BELOW_DB
    }

    /** The peaks [isPhantom] compares, for the log. */
    fun peaks(line: TranscriptSegment, left: DoubleArray, right: DoubleArray): Pair<Double, Double> {
        val from = (line.startMs / ChannelSides.FRAME_MS).toInt().coerceAtLeast(0)
        val to = (line.endMs / ChannelSides.FRAME_MS).toInt().coerceAtMost(minOf(left.size, right.size) - 1)
        if (to < from) return -200.0 to -200.0
        return (from..to).maxOf { left[it] } to (from..to).maxOf { right[it] }
    }
}
