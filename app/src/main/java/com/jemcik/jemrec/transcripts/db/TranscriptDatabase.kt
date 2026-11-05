package com.jemcik.jemrec.transcripts.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Transcripts, their lines, summaries and per-recording languages: the one
 * database in the app, because these are the one thing searched word by word.
 * Its shape follows CallVault's (schema 9), trimmed; it starts at version 1
 * here. Schemas are exported to app/schemas so each later version can be
 * migrated and tested against the one before.
 *
 * Version 2 (specs/003-voice-labels): where a transcript's speaker labels
 * came from, and which voice model made them ([MIGRATION_1_2]).
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
    version = 2,
    exportSchema = true,
)
abstract class TranscriptDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
    abstract fun summaries(): CallSummaryDao
    abstract fun languages(): RecordingLanguageDao

    companion object {
        const val FILE = "transcripts.db"

        /**
         * Adds voiceSource and voiceModel. A transcript with a You or Them
         * line was named from its channels - the only way there was - and
         * any other was not named at all.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN voiceSource TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE transcripts ADD COLUMN voiceModel TEXT")
                db.execSQL(
                    "UPDATE transcripts SET voiceSource = 'channels' WHERE recording IN " +
                        "(SELECT recording FROM transcript_segments WHERE speaker IN ('you', 'them'))"
                )
            }
        }

        @Volatile private var instance: TranscriptDatabase? = null

        /** The database's files on disk, its write-ahead log included. */
        fun bytesUsed(context: Context): Long {
            val db = context.getDatabasePath(FILE)
            return listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).filter { it.isFile }.sumOf { it.length() }
        }

        fun of(context: Context): TranscriptDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, TranscriptDatabase::class.java, FILE)
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
