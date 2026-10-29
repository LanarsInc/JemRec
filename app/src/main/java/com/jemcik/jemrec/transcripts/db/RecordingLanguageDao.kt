package com.jemcik.jemrec.transcripts.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RecordingLanguageDao {

    @Query("SELECT language FROM recording_language WHERE recording = :recording")
    suspend fun languageFor(recording: String): String?

    @Upsert
    suspend fun set(entry: RecordingLanguageEntry)

    @Query("SELECT recording FROM recording_language")
    suspend fun recordings(): List<String>

    @Query("DELETE FROM recording_language WHERE recording = :recording")
    suspend fun deleteFor(recording: String)
}
