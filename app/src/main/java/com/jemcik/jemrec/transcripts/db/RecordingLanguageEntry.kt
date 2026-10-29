package com.jemcik.jemrec.transcripts.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The language chosen for one recording, over the Settings one. Kept after
 * it is used, so Transcribe again uses it too. An ISO code, or "auto".
 */
@Entity(tableName = "recording_language")
data class RecordingLanguageEntry(
    @PrimaryKey val recording: String,
    val language: String,
)
