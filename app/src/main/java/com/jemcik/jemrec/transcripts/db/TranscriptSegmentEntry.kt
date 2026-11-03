package com.jemcik.jemrec.transcripts.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of a transcript. [idx] is its place in the transcript, which a
 * summary's points refer to; [id] is the row id the full-text index joins on.
 */
@Entity(
    tableName = "transcript_segments",
    indices = [Index(value = ["recording", "idx"], unique = true)],
)
data class TranscriptSegmentEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recording: String,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    /** "you", "them" or "none" (Speaker.key). */
    val speaker: String,
    val text: String,
)

/**
 * Full-text index over the lines. unicode61 so words are found in every
 * language offered - case folded, Norwegian's æ ø å and Cyrillic included.
 */
@Fts4(contentEntity = TranscriptSegmentEntry::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "transcript_segments_fts")
data class TranscriptSegmentFts(val text: String)
