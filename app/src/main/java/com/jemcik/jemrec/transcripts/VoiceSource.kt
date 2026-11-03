package com.jemcik.jemrec.transcripts

/**
 * Where a transcript's speaker labels came from (specs/003-voice-labels,
 * data-model.md). Kept with the transcript so a print made later knows which
 * transcripts it may name, and never undoes what the user marked.
 */
enum class VoiceSource(val key: String) {
    /** Not told apart: one mix with no voice step, or too little to tell. */
    NONE("none"),

    /** From the two channels: You on the left, Them on the right. */
    CHANNELS("channels"),

    /** Two voices told apart in a one-mix call, the owner's not known: Voice 1 and Voice 2. */
    VOICES("voices"),

    /** Two voices, the owner's picked by their print: You and Them. */
    PRINT("print"),

    /** Two voices, the owner's marked by the user. */
    MARKED("marked");

    companion object {
        fun of(key: String?): VoiceSource = entries.firstOrNull { it.key == key } ?: NONE
    }
}
