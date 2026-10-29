package com.jemcik.jemrec.transcribe

import android.content.Context
import android.net.Uri
import androidx.room.Room
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

/** A recording through the runner with a stand-in for whisper: what is asked, what is saved. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class TranscriptionRunnerTest {

    private lateinit var db: TranscriptDatabase
    private lateinit var repo: TranscriptRepository
    private lateinit var settings: TranscribeSettings
    private val asked = mutableListOf<List<Channel>>()
    private var aborted = false
    private var clockMs = 0L

    /** Levels of a conversation taking turns, so the sides read as apart. */
    private val apart = DoubleArray(3000) { if ((it / 100) % 2 == 0) -25.0 else -70.0 }.let { l ->
        l to DoubleArray(l.size) { if (l[it] > -50) -70.0 else -25.0 }
    }

    @Before fun open() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, TranscriptDatabase::class.java).allowMainThreadQueries().build()
        repo = TranscriptRepository(db)
        settings = TranscribeSettings(app.getSharedPreferences("runner-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    }

    @After fun close() = db.close()

    private fun runner(levels: Pair<DoubleArray, DoubleArray>?, output: (List<Channel>) -> List<TranscriptSegment>) =
        TranscriptionRunner(
            repository = repo,
            settings = settings,
            transcriber = { _, _, _, _, channels, _ ->
                asked += channels
                clockMs += 30_000
                output(channels)
            },
            sideLevels = { levels },
            durationMs = { 60_000 },
            wasAborted = { aborted },
            setupMs = { 2_000 },
            clock = { clockMs },
            now = { 42 },
        )

    private fun request(sides: Sides = Sides.AUTO, language: String? = "no") =
        RunRequest("a.ogg", Uri.parse("content://x/a.ogg"), "small-q5_1", "/m", language, "Kari", sides, 4)

    private fun sideLines(channels: List<Channel>) = channels.flatMap { ch ->
        val who = TranscriptionEngine.speakerOf(ch)
        listOf(TranscriptSegment(if (ch == Channel.RIGHT) 1000 else 0, 2000, "linje $ch", who))
    }

    @Test fun sidesApartAreTranscribedApartAndNamed() = runBlocking {
        val outcome = runner(apart, ::sideLines).run(request())
        assertEquals(RunOutcome.Done(2), outcome)
        assertEquals(listOf(listOf(Channel.LEFT, Channel.RIGHT)), asked)
        val t = repo.load("a.ogg")!!
        assertEquals(listOf(Speaker.YOU, Speaker.THEM), t.lines.map { it.speaker })
        assertTrue(t.facts.split)
        assertEquals("no", t.facts.language)
        assertEquals(30_000L, t.facts.wallMs)
        assertEquals(2_000L, t.facts.loadMs)
    }

    @Test fun oneMixIsTranscribedOnceAndNamedNothing() = runBlocking {
        val same = apart.first to apart.first.copyOf()
        runner(same, ::sideLines).run(request())
        assertEquals(listOf(listOf(Channel.MIX)), asked)
        assertEquals(listOf(Speaker.NONE), repo.load("a.ogg")!!.lines.map { it.speaker })
    }

    @Test fun unreadableLevelsMeanTheMix() = runBlocking {
        runner(null, ::sideLines).run(request())
        assertEquals(listOf(listOf(Channel.MIX)), asked)
    }

    @Test fun theUsersSplitSettingSkipsTheCheck() = runBlocking {
        runner(null, ::sideLines).run(request(Sides.SPLIT))
        assertEquals(listOf(listOf(Channel.LEFT, Channel.RIGHT)), asked)
    }

    @Test fun aStoppedRunSavesNothing() = runBlocking {
        aborted = true
        assertEquals(RunOutcome.Stopped, runner(apart, ::sideLines).run(request()))
        assertNull(repo.load("a.ogg"))
    }

    @Test fun aFailureIsReportedAndSavesNothing() = runBlocking {
        val outcome = runner(apart) { error("model would not load") }.run(request())
        assertEquals(RunOutcome.Failed("model would not load"), outcome)
        assertNull(repo.load("a.ogg"))
    }

    @Test fun noSpeechIsSavedAsATranscriptWithNoLines() = runBlocking {
        assertEquals(RunOutcome.Done(0), runner(apart) { emptyList() }.run(request()))
        assertEquals(0, repo.load("a.ogg")!!.lines.size)
    }

    @Test fun detectingTheLanguageIsStoredAsAuto() = runBlocking {
        runner(apart, ::sideLines).run(request(language = null))
        assertEquals("auto", repo.load("a.ogg")!!.facts.language)
    }

    @Test fun thePhonesSpeedIsRemembered() = runBlocking {
        runner(apart, ::sideLines).run(request())
        // 28 s of work over 60 s of audio, blended with nothing before it.
        assertTrue(settings.measuredRtf("small-q5_1")!! > 0)
        assertEquals(2_000L, settings.measuredLoadMs("small-q5_1"))
    }
}
