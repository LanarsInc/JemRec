package com.jemcik.jemrec.summary

import android.content.Context
import androidx.room.Room
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.TranscriptSegment
import com.jemcik.jemrec.transcripts.TranscriptFacts
import com.jemcik.jemrec.transcripts.TranscriptRepository
import com.jemcik.jemrec.transcripts.db.TranscriptDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A transcript through the summary runner with a stand-in for the model: what it is asked and what is kept. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class SummaryRunnerTest {

    private lateinit var db: TranscriptDatabase
    private lateinit var repo: TranscriptRepository
    private lateinit var settings: TranscribeSettings
    private val prompts = mutableListOf<String>()
    private var free = 4_000_000_000L

    private val answer = """{"intent":"Avtale levering","summary":"Leveransen kommer på fredag.","keyPoints":["[0:05] Fredag mellom ni og elleve"],"decisions":["Fredag"],"actionItems":[],"keyFacts":["ni til elleve"]}"""

    @Before fun open() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, TranscriptDatabase::class.java).allowMainThreadQueries().build()
        repo = TranscriptRepository(db)
        settings = TranscribeSettings(app.getSharedPreferences("sum-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    }

    @After fun close() = db.close()

    private fun runner(output: String = answer) = SummaryRunner(
        repository = repo,
        settings = settings,
        host = { _, block -> block { prompt, _, _ -> prompts += prompt; output } },
        freeMemory = { free },
        wasAborted = { false },
        clock = { 0 },
        now = { 7 },
        deviceLanguage = { "en" },
    )

    private suspend fun transcript(language: String = "no") = repo.save(
        "a.ogg",
        TranscriptFacts("nb-small-q5_0", language, true, 20_000, 30_000, 900, 6, 1),
        listOf(
            TranscriptSegment(1_000, 4_000, "Hei, gjelder det leveransen?", Speaker.YOU),
            TranscriptSegment(5_000, 9_000, "Ja, den kommer på fredag mellom ni og elleve.", Speaker.THEM),
        ),
    )

    @Test fun aNorwegianCallIsSummarisedInNorwegianAndKept() = runBlocking {
        transcript()
        val outcome = runner().run("a.ogg", "Priya Raman", SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(outcome is SummaryOutcome.Done)
        assertTrue(prompts.single().contains("Norwegian (Bokmål)"))
        // The sides by name, as JemRec told them apart.
        assertTrue(prompts.single().contains("You: Hei"))
        assertTrue(prompts.single().contains("Priya Raman: Ja"))
        val stored = repo.summary("a.ogg")!!
        assertEquals("no", stored.language)
        assertTrue(stored.searchText.contains("fredag"))
    }

    @Test fun qwenIsToldNotToThinkAloud() = runBlocking {
        transcript()
        runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(prompts.single().endsWith("/no_think"))
        prompts.clear()
        runner().run("a.ogg", null, SummaryModel.GEMMA_4_E2B_QAT_Q4_K_XL, "/m")
        assertTrue(!prompts.single().contains("/no_think"))
    }

    /** A number for a name is not a name: the other side is "Them". */
    @Test fun aNumberIsNotUsedAsTheOtherSidesName() = runBlocking {
        transcript()
        runner().run("a.ogg", "+44 7700 900461", SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(prompts.single().contains("Them: Ja"))
    }

    @Test fun tooLittleFreeMemoryStartsNothing() = runBlocking {
        transcript()
        free = 1_000_000_000L
        val outcome = runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(outcome is SummaryOutcome.NoRoom)
        assertTrue(prompts.isEmpty())
        assertNull(repo.summary("a.ogg"))
    }

    @Test fun noTranscriptNoSummary() = runBlocking {
        assertEquals(SummaryOutcome.NoTranscript, runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m"))
    }

    @Test fun unreadableOutputIsAFailureAndKeepsNothing() = runBlocking {
        transcript()
        val outcome = runner(output = "sorry").run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(outcome is SummaryOutcome.Failed)
        assertNull(repo.summary("a.ogg"))
    }
}
