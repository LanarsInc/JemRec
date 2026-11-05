package com.jemcik.jemrec.crm.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Version 1 to 2: Make private left the API on 2026-11-03, and its column
 * goes. A queue written at version 1 comes through with every call and its
 * states; Room checks on opening that the result is exactly the version 2
 * schema. Built from the exported schema as TranscriptMigrationTest does.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CrmMigrationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "crm-migration-test.db"

    @After fun delete() {
        context.deleteDatabase(name)
    }

    @Test fun theQueueComesThroughWithoutPrivate() = runBlocking {
        createVersion(1) { db ->
            db.execSQL(
                "INSERT INTO work_call (clientCallId, callLogId, userId, outgoing, answered, phone, presentation, countryIso, " +
                    "startedAtMs, durationSeconds, endedAtMs, choice, outcome, localState, dueAtMs, serverId, privateAsked, contentState, contentDueAtMs, attempts, byMachine) " +
                    "VALUES ('3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6c', 7, 1000001, 1, 1, '+47 400 00 001', 'ALLOWED', 'no', " +
                    "1000, 184, 185000, 'AUTO', 'REACHED', 'SENT', 0, 5012, 1, 'EXPECTED', 0, 2, 0)"
            )
        }
        val room = Room.databaseBuilder(context, CrmDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val c = room.calls().all().single()
            assertEquals("SENT", c.localState)
            assertEquals(5012, c.serverId)
            assertEquals("EXPECTED", c.contentState)
            assertEquals(2, c.attempts)
        } finally {
            room.close()
        }
    }

    /** Version 2 to 3: a revision for texts sent again (LANARS, 2026-11-05); every call kept has sent once. */
    @Test fun theQueueComesThroughWithRevisionZero() = runBlocking {
        createVersion(2) { db ->
            db.execSQL(
                "INSERT INTO work_call (clientCallId, callLogId, userId, outgoing, answered, phone, presentation, countryIso, " +
                    "startedAtMs, durationSeconds, endedAtMs, choice, outcome, localState, dueAtMs, serverId, contentState, contentDueAtMs, attempts, byMachine) " +
                    "VALUES ('3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6c', 7, 1000001, 1, 1, '+47 400 00 001', 'ALLOWED', 'no', " +
                    "1000, 184, 185000, 'AUTO', 'REACHED', 'SENT', 0, 5012, 'SENT_READY', 0, 0, 0)"
            )
        }
        val room = Room.databaseBuilder(context, CrmDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val c = room.calls().all().single()
            assertEquals("SENT_READY", c.contentState)
            assertEquals(0, c.contentRevision)
        } finally {
            room.close()
        }
    }

    /** A database at [version] exactly as Room made it: its tables, indices and identity hash. */
    private fun createVersion(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        val schema = JSONObject(File(SCHEMAS, "$version.json").readText()).getJSONObject("database")
        val statements = buildList {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                add(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) add(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                // The full-text tables' triggers that keep them in step with their content tables.
                entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                    for (j in 0 until triggers.length()) add(triggers.getString(j))
                }
            }
            val views = schema.optJSONArray("views")
            for (i in 0 until (views?.length() ?: 0)) add(views!!.getJSONObject(i).getString("createSql"))
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) add(setup.getString(i))
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper -> fill(helper.writableDatabase) }
    }

    private companion object {
        /** Gradle runs the JVM tests from the module's directory. */
        val SCHEMAS = File("schemas/com.jemcik.jemrec.crm.db.CrmDatabase")
    }
}
