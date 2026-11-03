package com.jemcik.jemrec.transcripts.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A word found in a transcript or a summary: which recording, where in it
 * (0 for a summary), and the words around it with the match between
 * [SearchMarks.START] and [SearchMarks.END].
 */
data class TranscriptSearchHit(
    val recording: String,
    val startMs: Long,
    val snippet: String,
)

/** A transcript's key and age, without its lines. */
data class StoredTranscript(val recording: String, val createdAt: Long)

/** The characters full-text search puts around a match; control characters, so no text has them. */
object SearchMarks {
    const val START = '\u0002'
    const val END = '\u0003'
}

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM transcripts WHERE recording = :recording")
    fun observe(recording: String): Flow<TranscriptEntry?>

    @Query("SELECT * FROM transcripts WHERE recording = :recording")
    suspend fun transcript(recording: String): TranscriptEntry?

    @Query("SELECT * FROM transcript_segments WHERE recording = :recording ORDER BY idx")
    fun observeSegments(recording: String): Flow<List<TranscriptSegmentEntry>>

    @Query("SELECT * FROM transcript_segments WHERE recording = :recording ORDER BY idx")
    suspend fun segments(recording: String): List<TranscriptSegmentEntry>

    /** Every transcript's recording and when it was made, for [TranscriptRepository.prune]. */
    @Query("SELECT recording, createdAt FROM transcripts")
    suspend fun stored(): List<StoredTranscript>

    /** Which of the recordings shown have a transcript, for the list's mark and chip. */
    @Query("SELECT recording FROM transcripts")
    fun observeTranscribed(): Flow<List<String>>

    @Upsert
    suspend fun upsertTranscript(entry: TranscriptEntry)

    @Insert
    suspend fun insertSegments(segments: List<TranscriptSegmentEntry>)

    @Query("DELETE FROM transcript_segments WHERE recording = :recording")
    suspend fun deleteSegments(recording: String)

    @Query("DELETE FROM transcripts WHERE recording = :recording")
    suspend fun deleteTranscript(recording: String)

    /**
     * A whole transcript in one transaction: the old one's lines go, the new
     * ones and the row come in together, so a reader never sees a mix.
     */
    @Transaction
    suspend fun replace(entry: TranscriptEntry, segments: List<TranscriptSegmentEntry>) {
        deleteSegments(entry.recording)
        insertSegments(segments)
        upsertTranscript(entry)
    }

    @Transaction
    suspend fun deleteFor(recording: String) {
        deleteSegments(recording)
        deleteTranscript(recording)
    }

    /**
     * Every matching line, in order. [query] is an FTS4 MATCH expression;
     * TranscriptSearch builds it from what was typed and keeps the first
     * line per recording - here SQLite refuses snippet() under GROUP BY.
     */
    @Query(
        """
        SELECT s.recording AS recording, s.startMs AS startMs,
               snippet(transcript_segments_fts, char(2), char(3), '…', -1, 10) AS snippet
        FROM transcript_segments_fts
        JOIN transcript_segments AS s ON s.id = transcript_segments_fts.rowid
        WHERE transcript_segments_fts MATCH :query
        ORDER BY s.recording, s.startMs
        """
    )
    suspend fun search(query: String): List<TranscriptSearchHit>
}
