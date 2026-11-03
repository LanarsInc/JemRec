package com.jemcik.jemrec.transcribe

/**
 * Who said a line: the phone's owner, the other person, one of two voices
 * whose owner is not known, or not known at all.
 *
 * VOICE1 and VOICE2 came with telling the voices of a one-mix call apart
 * (specs/003-voice-labels). Where the code switches on a speaker, each new
 * value does what NONE did, or is named as NONE is, so calls with two
 * channels come out as before:
 * - QuietSide: no channel of its own, so never a phantom (as NONE).
 * - LineCleanup: neither YOU nor THEM, so no echo test; repeats are dropped
 *   per voice, as per side. It runs before the voice step in any case.
 * - TurnMerge: sorted by ordinal at equal starts, after the three before.
 * - The transcript screen: "Voice 1" / "Voice 2", in You's and Them's colours.
 * - Copy, share and the summary's lines (SummaryRunner.linesOf): "Voice 1" /
 *   "Voice 2".
 * - The Sales Engine (ContentText): "Speaker 1" / "Speaker 2".
 * - The database keeps [key]; an older app reads an unknown key as NONE.
 */
enum class Speaker(val key: String) {
    /** The left channel, where JemRec puts the phone's own side; or the voice that matched the owner's print, or was marked as theirs. */
    YOU("you"),

    /** The right channel: the other person; or the voice that is not the owner's in a one-mix call. */
    THEM("them"),

    /** Both sides in one mix, so nothing says who. */
    NONE("none"),

    /** In a one-mix call, the voice heard first, its owner not known. */
    VOICE1("voice1"),

    /** In a one-mix call, the other voice. */
    VOICE2("voice2");

    companion object {
        fun of(key: String?): Speaker = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * One contiguous piece of recognised speech, timed from the start of the
 * recording.
 *
 * Segments rather than one blob of text, as in CallVault: the transcript
 * seeks playback to the line tapped, and a summary points back at lines.
 * The speaker is JemRec's: which channel the line came from, when the two
 * sides were transcribed apart ([ChannelSides]).
 */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: Speaker = Speaker.NONE,
)
