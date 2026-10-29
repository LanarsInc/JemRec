package com.jemcik.jemrec.transcripts

import androidx.room.Room
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscriptSegment
import com.jemcik.jemrec.transcripts.db.CallSummaryEntry
import com.jemcik.jemrec.transcripts.db.SearchMarks
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

/**
 * The database's promises, on a real SQLite: a transcript is replaced whole,
 * deleting a recording's text takes all of it, and words are found in the
 * languages offered - Norwegian's æ ø å and Cyrillic included.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class TranscriptRepositoryTest {

    private lateinit var db: TranscriptDatabase
    private lateinit var repo: TranscriptRepository

    private val facts = TranscriptFacts(
        modelId = "small-q5_1", language = "no", split = true,
        audioMs = 60_000, wallMs = 50_000, loadMs = 900, threads = 6, createdAt = 1,
    )

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TranscriptRepository(db)
    }

    @After fun close() = db.close()

    private fun line(ms: Long, text: String, who: Speaker = Speaker.THEM) = TranscriptSegment(ms, ms + 1000, text, who)

    @Test fun savedLinesComeBackInOrderWithTheirSpeakers() = runBlocking {
        repo.save("a.ogg", facts, listOf(line(0, "Hei", Speaker.YOU), line(1000, "  Hallo  "), line(2000, "   ")))
        val t = repo.load("a.ogg")!!
        assertEquals(listOf("Hei", "Hallo"), t.lines.map { it.text })
        assertEquals(listOf(Speaker.YOU, Speaker.THEM), t.lines.map { it.speaker })
        assertEquals(facts, t.facts)
    }

    @Test fun transcribingAgainReplacesTheLinesAndDropsTheOldSummary() = runBlocking {
        repo.save("a.ogg", facts, listOf(line(0, "første"), line(1000, "andre")))
        repo.saveSummary(CallSummaryEntry("a.ogg", "qwen", "no", "{}", "noe", 1, 1))
        repo.save("a.ogg", facts.copy(modelId = "turbo-q8_0"), listOf(line(0, "ny")))
        val t = repo.load("a.ogg")!!
        assertEquals(listOf("ny"), t.lines.map { it.text })
        assertEquals("turbo-q8_0", t.facts.modelId)
        assertNull(repo.summary("a.ogg"))
    }

    @Test fun deletingTakesLinesSummaryAndLanguage() = runBlocking {
        repo.save("a.ogg", facts, listOf(line(0, "Hei")))
        repo.saveSummary(CallSummaryEntry("a.ogg", "qwen", "no", "{}", "noe", 1, 1))
        repo.setLanguage("a.ogg", "uk")
        repo.deleteFor("a.ogg")
        assertNull(repo.load("a.ogg"))
        assertNull(repo.summary("a.ogg"))
        assertNull(repo.languageFor("a.ogg"))
        assertTrue(db.transcripts().search("Hei").isEmpty())
    }

    @Test fun findsNorwegianAndUkrainianWords() = runBlocking {
        repo.save("no.ogg", facts, listOf(line(0, "Leveransen kommer på fredag"), line(5000, "Vi møtes på kontoret")))
        repo.save("uk.ogg", facts.copy(language = "uk"), listOf(line(0, "Доставка буде в п'ятницю")))
        val no = db.transcripts().search("møtes")
        assertEquals(listOf("no.ogg"), no.map { it.recording })
        assertTrue(no.single().snippet.contains("${SearchMarks.START}møtes${SearchMarks.END}"))
        assertEquals(listOf("uk.ogg"), db.transcripts().search("доставка").map { it.recording })
    }

    @Test fun findsSummaryWords() = runBlocking {
        repo.save("a.ogg", facts, listOf(line(0, "Hei")))
        repo.saveSummary(CallSummaryEntry("a.ogg", "qwen", "no", "{}", "Avtale om levering på fredag", 1, 1))
        assertEquals(listOf("a.ogg"), db.summaries().search("levering").map { it.recording })
    }

    @Test fun pruneTakesTheTextOfRecordingsNoLongerListed() = runBlocking {
        repo.save("gone.ogg", facts, listOf(line(0, "borte")))
        repo.save("here.ogg", facts, listOf(line(0, "her")))
        repo.setLanguage("gone.ogg", "uk")
        repo.setLanguage("never.ogg", "en")
        assertEquals(1, repo.prune(listOf("here.ogg"), now = 1 + 3_600_000))
        assertNull(repo.load("gone.ogg"))
        assertNull(repo.languageFor("gone.ogg"))
        assertNull(repo.languageFor("never.ogg"))
        assertEquals("her", repo.load("here.ogg")!!.lines.single().text)
    }

    @Test fun aLanguageForOneRecordingIsKeptAndCanBeCleared() = runBlocking {
        repo.setLanguage("a.ogg", "no")
        assertEquals("no", repo.languageFor("a.ogg"))
        repo.setLanguage("a.ogg", null)
        assertNull(repo.languageFor("a.ogg"))
    }
}
