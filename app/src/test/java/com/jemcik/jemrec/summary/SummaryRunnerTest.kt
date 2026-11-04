package com.jemcik.jemrec.summary

import android.content.Context
import androidx.room.Room
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.TranscriptSegment
import com.jemcik.jemrec.transcripts.Transcript
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
        pause = { ms -> pausedMs += ms; afterPause() },
    )

    private var pausedMs = 0L
    private var afterPause: () -> Unit = {}

    private suspend fun transcript(language: String = "no") = repo.save(
        "a.ogg",
        TranscriptFacts("nb-small-q5_0", language, true, 20_000, 30_000, 900, 6, 1),
        listOf(
            TranscriptSegment(1_000, 4_000, "Hei, gjelder det leveransen?", Speaker.YOU),
            TranscriptSegment(5_000, 9_000, "Ja, den kommer på fredag mellom ni og elleve.", Speaker.THEM),
            TranscriptSegment(10_000, 12_000, "Flott, takk skal du ha.", Speaker.YOU),
        ),
    )

    /** A few words are not given to a model, which would invent the rest (2026-11-04). */
    @Test fun aShortCallIsNotGivenToTheModel() = runBlocking {
        repo.save(
            "a.ogg",
            TranscriptFacts("nb-small-q5_0", "no", true, 3_000, 3_000, 900, 6, 1),
            listOf(TranscriptSegment(0, 2_000, "Hallo? Jeg ringer deg tilbake.", Speaker.THEM)),
        )
        free = 1_000_000_000L
        val outcome = runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(outcome is SummaryOutcome.Done)
        assertEquals("Short call", (outcome as SummaryOutcome.Done).summary.intent)
        assertTrue(prompts.isEmpty())
        assertEquals(SummaryRunner.NO_MODEL, repo.summary("a.ogg")?.modelId)
    }

    @Test fun thePromptSaysWhatToDoWithARecordedMenu() = runBlocking {
        transcript()
        runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(prompts.single().contains("Automated message:"))
    }

    /** In English since 1.0, whatever the call's language (FR-033, specs/002): the Sales Engine takes English. */
    @Test fun aNorwegianCallIsSummarisedInEnglishAndKept() = runBlocking {
        transcript()
        val outcome = runner().run("a.ogg", "Priya Raman", SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(outcome is SummaryOutcome.Done)
        assertTrue(prompts.single().contains("write EVERY text field in English"))
        assertTrue(prompts.single().contains("The call itself may be in another language"))
        assertTrue(!prompts.single().contains("Norwegian (Bokmål)"))
        // The sides by name, as JemRec told them apart.
        assertTrue(prompts.single().contains("You: Hei"))
        assertTrue(prompts.single().contains("Priya Raman: Ja"))
        val stored = repo.summary("a.ogg")!!
        assertEquals("en", stored.language)
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

    /** A one-mix call's voices not yet named: two people, one of them the owner, neither "you". */
    @Test fun voicesAreTwoPeopleNotYou() = runBlocking {
        repo.save(
            "a.ogg",
            TranscriptFacts("small-q5_1", "no", false, 20_000, 30_000, 900, 6, 1),
            listOf(
                TranscriptSegment(1_000, 4_000, "Hei, gjelder det leveransen?", Speaker.VOICE1),
                TranscriptSegment(5_000, 9_000, "Ja, den kommer på fredag mellom ni og elleve om morgenen.", Speaker.VOICE2),
            ),
        )
        runner().run("a.ogg", "Kari", SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        val prompt = prompts.single()
        assertTrue(prompt.contains("Voice 1: Hei") && prompt.contains("Voice 2: Ja"))
        assertTrue(prompt.contains("which one is not known"))
        assertTrue(!prompt.contains("write about them in the second person"))
    }

    /** The calls named by their channels keep the second person for "You". */
    @Test fun namedSidesAreStillYou() = runBlocking {
        transcript()
        runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(prompts.single().contains("write about them in the second person"))
    }

    /** A number for a name is not a name: the other side is "Them". */
    @Test fun aNumberIsNotUsedAsTheOtherSidesName() = runBlocking {
        transcript()
        runner().run("a.ogg", "+44 7700 900461", SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(prompts.single().contains("Them: Ja"))
    }

    /** A one-mix call's voices, not yet named, are Voice 1 and Voice 2 in its lines - never the contact. */
    @Test fun unnamedVoicesAreVoice1AndVoice2() {
        val facts = TranscriptFacts("m", "en", split = false, audioMs = 1, wallMs = 1, loadMs = 0, threads = 1, createdAt = 0)
        val lines = SummaryRunner.linesOf(
            Transcript("a.ogg", facts, listOf(
                TranscriptSegment(0, 1_000, "Hi.", Speaker.VOICE1),
                TranscriptSegment(1_000, 2_000, "Hello.", Speaker.VOICE2),
                TranscriptSegment(2_000, 3_000, "Hm.", Speaker.NONE),
            )),
            contact = "Ola",
        )
        assertEquals(listOf("Voice 1", "Voice 2", null), lines.map { it.speaker })
    }

    @Test fun tooLittleFreeMemoryStartsNothing() = runBlocking {
        transcript()
        free = 1_000_000_000L
        val outcome = runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(outcome is SummaryOutcome.NoRoom)
        assertEquals(SummaryRunner.ROOM_WAIT_MS, pausedMs)
        assertTrue(prompts.isEmpty())
        assertNull(repo.summary("a.ogg"))
    }

    /** Right after the voice step the memory comes back within seconds (A24, 2026-11-04): the summary waits for it. */
    @Test fun memoryFreedWhileWaitingIsUsed() = runBlocking {
        transcript()
        free = 1_334_000_000L
        afterPause = { if (pausedMs >= 2 * SummaryRunner.ROOM_POLL_MS) free = 2_295_000_000L }
        val outcome = runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m")
        assertTrue(outcome is SummaryOutcome.Done)
        assertEquals(2 * SummaryRunner.ROOM_POLL_MS, pausedMs)
    }

    @Test fun cancelWhileWaitingForMemoryStops() = runBlocking {
        transcript()
        free = 1_000_000_000L
        var stop = false
        afterPause = { stop = true }
        val outcome = runner().run("a.ogg", null, SummaryModel.QWEN3_1_7B_Q4_K_M, "/m", shouldStop = { stop })
        assertEquals(SummaryOutcome.Stopped, outcome)
        assertTrue(prompts.isEmpty())
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

    @Test fun outputKeysNeverOverwriteInput() {
        assertTrue(SummaryWorker.INPUT_KEYS.intersect(SummaryWorker.OUTPUT_KEYS).isEmpty())
    }
}
