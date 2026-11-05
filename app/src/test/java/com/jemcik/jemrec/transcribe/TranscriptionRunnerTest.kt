package com.jemcik.jemrec.transcribe

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.jemcik.jemrec.transcripts.TranscriptRepository
import com.jemcik.jemrec.transcripts.VoiceSource
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
    /** Each pass: the model's path, the language pinned, the sides. */
    private val passes = mutableListOf<Triple<String, String?, List<Channel>>>()
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

    private fun runner(
        levels: Pair<DoubleArray, DoubleArray>?,
        detected: Map<Channel, List<Pair<String, Double>>> = emptyMap(),
        voices: VoiceTeller? = null,
        learnVoice: (suspend (Uri, List<TranscriptSegment>, Double) -> Unit)? = null,
        output: (List<Channel>) -> List<TranscriptSegment>,
    ) =
        TranscriptionRunner(
            repository = repo,
            settings = settings,
            transcriber = { _, modelPath, language, _, channels, _ ->
                asked += channels
                passes += Triple(modelPath, language, channels)
                clockMs += 30_000
                output(channels)
            },
            detector = { _, _, _ -> detected },
            modelFor = { language -> if (language == "no") ReadyModel("nb-small-q5_0", "NB-Whisper small", java.io.File("/nb")) else null },
            sideLevels = { levels },
            durationMs = { 60_000 },
            wasAborted = { aborted },
            forgetAbort = { aborted = false },
            setupMs = { 2_000 },
            clock = { clockMs },
            now = { 42 },
            voices = voices,
            learnVoice = learnVoice,
        )

    private fun request(sides: Sides = Sides.AUTO, language: String? = "no") =
        RunRequest("a.ogg", Uri.parse("content://x/a.ogg"), "small-q5_1", "/m", language, "Kari", sides, 4)

    private fun sideLines(channels: List<Channel>) = channels.flatMap { ch ->
        val who = TranscriptionEngine.speakerOf(ch)
        listOf(TranscriptSegment(if (ch == Channel.RIGHT) 1000 else 0, 2000, "linje $ch", who))
    }

    @Test fun sidesApartAreTranscribedApartAndNamed() = runBlocking {
        val outcome = runner(apart, output = ::sideLines).run(request())
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
        runner(same, output = ::sideLines).run(request())
        assertEquals(listOf(listOf(Channel.MIX)), asked)
        assertEquals(listOf(Speaker.NONE), repo.load("a.ogg")!!.lines.map { it.speaker })
    }

    /** A teller that splits the one line into Voice 1 and Voice 2, and notes what it was given. */
    private val told = mutableListOf<List<TranscriptSegment>>()
    private val twoVoices = VoiceTeller { _, _, lines, _ ->
        told += lines
        VoiceResult(
            listOf(lines.single().copy(endMs = 1000, text = "hei", speaker = Speaker.VOICE1), lines.single().copy(startMs = 1000, text = "hallo", speaker = Speaker.VOICE2)),
            VoiceSource.VOICES, "voices-test", emptyList(),
        )
    }

    /** The voices of a one-mix call are told apart after its lines are saved, and replace them. */
    @Test fun oneMixGetsItsVoicesTold() = runBlocking {
        val same = apart.first to apart.first.copyOf()
        assertEquals(RunOutcome.Done(1), runner(same, voices = twoVoices, output = ::sideLines).run(request()))
        assertEquals(listOf(listOf("linje MIX")), told.map { lines -> lines.map { it.text } })
        val t = repo.load("a.ogg")!!
        assertEquals(listOf(Speaker.VOICE1, Speaker.VOICE2), t.lines.map { it.speaker })
        assertEquals(VoiceSource.VOICES, t.facts.voiceSource)
    }

    /** A call with two channels has its names: the voice step is not run (SC-006). */
    @Test fun sidesApartNeverReachTheVoiceStep() = runBlocking {
        runner(apart, voices = twoVoices, output = ::sideLines).run(request())
        assertTrue(told.isEmpty())
        assertEquals(VoiceSource.CHANNELS, repo.load("a.ogg")!!.facts.voiceSource)
    }

    /** Not told apart, or failing: the transcript stays as saved, unnamed - never lost. */
    @Test fun aVoiceStepThatGivesNothingOrFailsLeavesTheLinesUnnamed() = runBlocking {
        val same = apart.first to apart.first.copyOf()
        runner(same, voices = { _, _, _, _ -> null }, output = ::sideLines).run(request())
        assertEquals(listOf(Speaker.NONE), repo.load("a.ogg")!!.lines.map { it.speaker })
        val outcome = runner(same, voices = { _, _, _, _ -> error("model would not load") }, output = ::sideLines).run(request())
        assertEquals(RunOutcome.Done(1), outcome)
        assertEquals(listOf(Speaker.NONE), repo.load("a.ogg")!!.lines.map { it.speaker })
        assertEquals(VoiceSource.NONE, repo.load("a.ogg")!!.facts.voiceSource)
    }

    /** A call with its sides apart is offered to the owner's voice print; a one-mix call is not. */
    @Test fun onlySidesApartAreOfferedToThePrint() = runBlocking {
        val offered = mutableListOf<Pair<List<Speaker>, Double>>()
        val learn: suspend (Uri, List<TranscriptSegment>, Double) -> Unit = { _, lines, correlation -> offered += lines.map { it.speaker } to correlation }
        runner(apart, learnVoice = learn, output = ::sideLines).run(request())
        assertEquals(1, offered.size)
        assertEquals(listOf(Speaker.YOU, Speaker.THEM), offered.single().first)
        // The sides take turns: their levels move apart.
        assertTrue(offered.single().second < 0)
        runner(apart.first to apart.first.copyOf(), learnVoice = learn, output = ::sideLines).run(request())
        assertEquals(1, offered.size)
    }

    @Test fun unreadableLevelsMeanTheMix() = runBlocking {
        runner(null, output = ::sideLines).run(request())
        assertEquals(listOf(listOf(Channel.MIX)), asked)
    }

    @Test fun theUsersSplitSettingSkipsTheCheck() = runBlocking {
        runner(null, output = ::sideLines).run(request(Sides.SPLIT))
        assertEquals(listOf(listOf(Channel.LEFT, Channel.RIGHT)), asked)
    }

    @Test fun aStoppedRunSavesNothing() = runBlocking {
        val outcome = runner(apart) { channels -> aborted = true; sideLines(channels) }.run(request())
        assertEquals(RunOutcome.Stopped, outcome)
        assertNull(repo.load("a.ogg"))
    }

    /** Cancel during the voice step left the abort set; the next run, language pinned, stopped at once (A24, 2026-11-04). */
    @Test fun anAbortLeftFromAnEarlierRunDoesNotStopThisOne() = runBlocking {
        aborted = true
        assertEquals(RunOutcome.Done(2), runner(apart, output = ::sideLines).run(request(language = "en")))
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

    @Test fun aLanguageWhisperCannotTellIsStoredAsAuto() = runBlocking {
        runner(apart, output = ::sideLines).run(request(language = null))
        assertEquals("auto", repo.load("a.ogg")!!.facts.language)
        assertEquals(listOf(Triple("/m", null, listOf(Channel.LEFT, Channel.RIGHT))), passes)
    }

    /** The point of detecting first: a Norwegian call gets the model made for Norwegian, and the language pinned. */
    @Test fun aDetectedLanguageIsPinnedWithItsOwnModel() = runBlocking {
        val same = apart.first to apart.first.copyOf()
        runner(same, detected = mapOf(Channel.MIX to listOf("no" to 0.9, "da" to 0.05)), output = ::sideLines).run(request(language = null))
        assertEquals(listOf(Triple("/nb", "no", listOf(Channel.MIX))), passes)
        val facts = repo.load("a.ogg")!!.facts
        assertEquals("no", facts.language)
        assertEquals("nb-small-q5_0", facts.modelId)
    }

    @Test fun twoSidesInTwoLanguagesAreEachPinnedAndFiledUnderTheLongerOne() = runBlocking {
        val detected = mapOf(Channel.LEFT to listOf("ru" to 0.95), Channel.RIGHT to listOf("no" to 0.8))
        runner(apart, detected) { channels ->
            channels.map { ch -> TranscriptSegment(0, 2000, if (ch == Channel.RIGHT) "en lang norsk setning" else "да", TranscriptionEngine.speakerOf(ch)) }
        }.run(request(language = null))
        assertEquals(listOf(Triple("/m", "ru", listOf(Channel.LEFT)), Triple("/nb", "no", listOf(Channel.RIGHT))), passes)
        assertEquals(listOf(Speaker.YOU, Speaker.THEM), repo.load("a.ogg")!!.lines.map { it.speaker })
        assertEquals("no", repo.load("a.ogg")!!.facts.language)
        // Two models' speed together is neither's.
        assertNull(settings.measuredRtf("small-q5_1"))
    }

    /** A side with too little speech to tell takes the other's language: one pass, not a second on a few words. */
    @Test fun aSideTooQuietToTellFollowsTheOther() = runBlocking {
        runner(apart, detected = mapOf(Channel.LEFT to listOf("ru" to 0.97), Channel.RIGHT to emptyList()), output = ::sideLines)
            .run(request(language = null))
        // The stand-in's Latin lines are retried as the wrong script for Russian; every try is the one pass.
        assertEquals(setOf(Triple("/m", "ru", listOf(Channel.LEFT, Channel.RIGHT))), passes.toSet())
    }

    /** A side heard in another language than the other's, if unsure, is left to whisper - not pinned to the wrong one. */
    @Test fun anUnsureSideInAnotherLanguageIsLeftToWhisper() = runBlocking {
        val detected = mapOf(Channel.LEFT to listOf("ru" to 0.9), Channel.RIGHT to listOf("en" to 0.3, "de" to 0.1))
        runner(apart, detected) { channels ->
            channels.map { ch -> TranscriptSegment(0, 2000, if (ch == Channel.LEFT) "да" else "news", TranscriptionEngine.speakerOf(ch)) }
        }.run(request(language = null))
        assertEquals(listOf(Triple("/m", "ru", listOf(Channel.LEFT)), Triple("/m", null, listOf(Channel.RIGHT))), passes)
    }

    @Test fun aGuessWhisperIsUnsureOfIsNotPinned() = runBlocking {
        val same = apart.first to apart.first.copyOf()
        runner(same, detected = mapOf(Channel.MIX to listOf("no" to 0.3, "da" to 0.3)), output = ::sideLines).run(request(language = null))
        assertEquals(listOf(Triple("/m", null, listOf(Channel.MIX))), passes)
    }

    @Test fun aPinnedLanguageIsNotDetected() = runBlocking {
        runner(apart, detected = mapOf(Channel.LEFT to listOf("ru" to 0.99)), output = ::sideLines).run(request(language = "en"))
        assertEquals(listOf(Triple("/m", "en", listOf(Channel.LEFT, Channel.RIGHT))), passes)
    }

    @Test fun thePhonesSpeedIsRemembered() = runBlocking {
        runner(apart, output = ::sideLines).run(request())
        // 28 s of work over 60 s of audio, blended with nothing before it.
        assertTrue(settings.measuredRtf("small-q5_1")!! > 0)
        assertEquals(2_000L, settings.measuredLoadMs("small-q5_1"))
    }
}
