package com.jemcik.jemrec.transcribe

/**
 * The two sides' lines as one conversation: in the order they were said, the
 * phone's owner first when two start together. Lines with no words go.
 */
object TurnMerge {
    fun merge(vararg sides: List<TranscriptSegment>): List<TranscriptSegment> =
        sides.flatMap { it }
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<TranscriptSegment> { it.startMs }.thenBy { it.speaker.ordinal })
}
