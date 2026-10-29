package com.jemcik.jemrec.transcripts.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Transcripts, their lines, summaries and per-recording languages: the one
 * database in the app, because these are the one thing searched word by word.
 * Its shape follows CallVault's (schema 9), trimmed; it starts at version 1
 * here. Schemas are exported to app/schemas so each later version can be
 * migrated and tested against the one before.
 */
@Database(
    entities = [
        TranscriptEntry::class,
        TranscriptSegmentEntry::class,
        TranscriptSegmentFts::class,
        CallSummaryEntry::class,
        CallSummaryFts::class,
        RecordingLanguageEntry::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TranscriptDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
    abstract fun summaries(): CallSummaryDao
    abstract fun languages(): RecordingLanguageDao

    companion object {
        const val FILE = "transcripts.db"

        @Volatile private var instance: TranscriptDatabase? = null

        fun of(context: Context): TranscriptDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, TranscriptDatabase::class.java, FILE)
                    .build().also { instance = it }
            }
    }
}
