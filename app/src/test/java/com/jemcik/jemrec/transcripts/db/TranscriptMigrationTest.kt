package com.jemcik.jemrec.transcripts.db

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
 * Version 1 to 2 (specs/003-voice-labels): a database the app wrote before
 * telling voices apart comes through with every transcript, its lines
 * untouched, and where their labels came from filled in.
 *
 * Version 1 is built from its exported schema (app/schemas/.../1.json), so
 * the test is against what phones really have, and then opened by Room at
 * version 2 with the migration: Room checks on opening that what the
 * migration left is exactly the version 2 schema, and fails otherwise. Not
 * Room's MigrationTestHelper: it reads the schemas from the APK's assets,
 * which the JVM tests do not have.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class TranscriptMigrationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"

    @After fun delete() {
        context.deleteDatabase(name)
    }

    @Test fun eachTranscriptSaysWhereItsLabelsCameFrom() = runBlocking {
        createVersion1 { db ->
            fun transcript(rec: String, sides: String) = db.execSQL(
                "INSERT INTO transcripts (recording, modelId, language, sides, audioMs, wallMs, loadMs, threads, createdAt) " +
                    "VALUES ('$rec', 'small-q5_1', 'no', '$sides', 1000, 1000, 10, 4, 1)"
            )
            fun line(rec: String, idx: Int, speaker: String) = db.execSQL(
                "INSERT INTO transcript_segments (recording, idx, startMs, endMs, speaker, text) " +
                    "VALUES ('$rec', $idx, ${idx * 1000}, ${idx * 1000 + 900}, '$speaker', 'line $idx')"
            )
            transcript("split.ogg", "split"); line("split.ogg", 0, "you"); line("split.ogg", 1, "them")
            transcript("mix.ogg", "mix"); line("mix.ogg", 0, "none")
            transcript("empty.ogg", "mix")
        }

        val room = Room.databaseBuilder(context, TranscriptDatabase::class.java, name)
            .addMigrations(TranscriptDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.transcripts()
            assertEquals("channels", dao.transcript("split.ogg")!!.voiceSource)
            assertEquals("none", dao.transcript("mix.ogg")!!.voiceSource)
            assertEquals("none", dao.transcript("empty.ogg")!!.voiceSource)
            assertEquals(null, dao.transcript("split.ogg")!!.voiceModel)
            assertEquals(listOf("you", "them"), dao.segments("split.ogg").map { it.speaker })
            assertEquals(1, dao.segments("mix.ogg").size)
        } finally {
            room.close()
        }
    }

    /** A database at version 1 exactly as Room made it: its tables, indices and identity hash. */
    private fun createVersion1(fill: (SupportSQLiteDatabase) -> Unit) {
        val schema = JSONObject(File(SCHEMAS, "1.json").readText()).getJSONObject("database")
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
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper -> fill(helper.writableDatabase) }
    }

    private companion object {
        /** Gradle runs the JVM tests from the module's directory. */
        val SCHEMAS = File("schemas/com.jemcik.jemrec.transcripts.db.TranscriptDatabase")
    }
}
