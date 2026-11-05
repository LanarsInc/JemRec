package com.jemcik.jemrec.crm.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

/**
 * The Sales Engine's queue: work calls and what the server said of them.
 *
 * A database of its own, not a table in transcripts.db: the two have nothing
 * to join, and a blocked phone must lose all of this and none of that (§2.2,
 * FR-043) - here a file is deleted, there nothing is touched.
 */
@Database(
    entities = [WorkCallEntry::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = CrmDatabase.NoPrivate::class),
        // Version 3 adds `contentRevision`, 0 for every call already kept: sent once.
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class CrmDatabase : RoomDatabase() {
    abstract fun calls(): WorkCallDao

    /**
     * Version 2 drops `privateAsked`: Make private left the API on 2026-11-03
     * ("it is a work phone"). Only debug installs ever had version 1.
     */
    @DeleteColumn(tableName = "work_call", columnName = "privateAsked")
    class NoPrivate : AutoMigrationSpec

    companion object {
        const val FILE = "crm.db"

        @Volatile private var instance: CrmDatabase? = null

        fun of(context: Context): CrmDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, CrmDatabase::class.java, FILE)
                    .build().also { instance = it }
            }

        /** For a test: a database in memory, used in place of the file. */
        internal fun useForTests(db: CrmDatabase) = synchronized(this) { instance = db }
    }
}
