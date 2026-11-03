package com.jemcik.jemrec.crm.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The Sales Engine's queue: work calls and what the server said of them.
 *
 * A database of its own, not a table in transcripts.db: the two have nothing
 * to join, and a blocked phone must lose all of this and none of that (§2.2,
 * FR-043) - here a file is deleted, there nothing is touched.
 */
@Database(entities = [WorkCallEntry::class], version = 1, exportSchema = true)
abstract class CrmDatabase : RoomDatabase() {
    abstract fun calls(): WorkCallDao

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
