package com.jemcik.jemrec.transcribe

/** Who said a line: the phone's owner, the other person, or not known. */
enum class Speaker(val key: String) {
    /** The left channel, where JemRec puts the phone's own side. */
    YOU("you"),

    /** The right channel: the other person. */
    THEM("them"),

    /** Both sides in one mix, so nothing says who. */
    NONE("none");

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
