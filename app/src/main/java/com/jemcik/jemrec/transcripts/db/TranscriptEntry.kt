package com.jemcik.jemrec.transcripts.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A finished transcript of one recording. A row exists only when the
 * transcript is complete: queueing and progress are WorkManager's, so a
 * killed run can never leave a half transcript that looks done.
 *
 * Keyed by the recording's file name (Recording.name), which JemRec gives
 * once and never changes. One per recording: Transcribe again replaces it,
 * and [modelId] says which model made the one there.
 */
@Entity(tableName = "transcripts")
data class TranscriptEntry(
    @PrimaryKey val recording: String,
    val modelId: String,
    /** What whisper was given: an ISO code, or "auto". */
    val language: String,
    /** "split" when the two sides were transcribed apart, "mix" when as one. */
    val sides: String,
    val audioMs: Long,
    /** The whole run, model load included. */
    val wallMs: Long,
    val loadMs: Long,
    val threads: Int,
    val createdAt: Long,
)
