package com.jemcik.jemrec.transcripts.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CallSummaryDao {

    @Query("SELECT * FROM call_summaries WHERE recording = :recording")
    fun observe(recording: String): Flow<CallSummaryEntry?>

    @Query("SELECT * FROM call_summaries WHERE recording = :recording")
    suspend fun summary(recording: String): CallSummaryEntry?

    @Upsert
    suspend fun upsert(entry: CallSummaryEntry)

    @Query("DELETE FROM call_summaries WHERE recording = :recording")
    suspend fun deleteFor(recording: String)

    @Query(
        """
        SELECT s.recording AS recording, 0 AS startMs,
               snippet(call_summaries_fts, char(2), char(3), '…', -1, 10) AS snippet
        FROM call_summaries_fts
        JOIN call_summaries AS s ON s.rowid = call_summaries_fts.rowid
        WHERE call_summaries_fts MATCH :query
        """
    )
    suspend fun search(query: String): List<TranscriptSearchHit>
}
