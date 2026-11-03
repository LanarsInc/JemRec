package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscriptSegment
import com.jemcik.jemrec.transcribe.model.SpeechModel
import com.jemcik.jemrec.transcripts.Transcript
import com.jemcik.jemrec.transcripts.TranscriptFacts
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.JobState
import com.jemcik.jemrec.ui.ModelState
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.TranscriptUi
import com.jemcik.jemrec.ui.TranscriptTab
import com.jemcik.jemrec.ui.SummaryModelState
import com.jemcik.jemrec.summary.CallSummary
import com.jemcik.jemrec.summary.SummaryModel
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The Transcript screen in each state a person meets it in: before the
 * model, while it downloads, ready, while a call is transcribed, done (with
 * the sides named, and as one mix), nothing said, and a run that failed.
 * The finished transcript in each look, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class TranscriptScreenshots : ScreenTest() {

    private val recording = Fixtures.recordings()[3].copy(whenLabel = "Yesterday, 14:16", durationMillis = 158_000)

    private val facts = TranscriptFacts(
        modelId = "nb-small-q5_0", language = "no", split = true,
        audioMs = 158_000, wallMs = 171_000, loadMs = 900, threads = 6, createdAt = 0,
    )

    /** An invented call, in Norwegian: a delivery arranged. */
    private val lines = listOf(
        TranscriptSegment(1_200, 3_000, "Hei, det er Priya fra lageret.", Speaker.THEM),
        TranscriptSegment(3_400, 5_100, "Hei! Gjelder det leveransen?", Speaker.YOU),
        TranscriptSegment(5_600, 11_800, "Ja, den kommer på fredag mellom ni og elleve, ikke torsdag som vi sa.", Speaker.THEM),
        TranscriptSegment(12_300, 15_900, "Fredag går fint. Kan dere ringe en halvtime før?", Speaker.YOU),
        TranscriptSegment(16_400, 19_000, "Selvfølgelig. Sjåføren ringer deg.", Speaker.THEM),
        TranscriptSegment(19_500, 20_600, "Tusen takk, ha det bra.", Speaker.YOU),
    )

    private fun draw(ui: TranscriptUi, look: LookChoice = LookChoice()) {
        val state = Fixtures.ready().copy(screen = Screen.TRANSCRIPT)
        val vm = viewModel(state)
        vm.transcripts.showForTest(ui.copy(recording = recording))
        show(look = look) { AppScaffold(state, vm) }
    }

    @Test fun beforeTheModel() {
        draw(TranscriptUi(model = ModelState.Missing(SpeechModel.NB_SMALL_Q5_0, SpeechModel.NB_SMALL_Q5_0.sizeBytes, 0)))
        snap("transcript-no-model")
    }

    @Test fun downloading() {
        draw(TranscriptUi(model = ModelState.Downloading(SpeechModel.NB_SMALL_Q5_0, 37)))
        snap("transcript-downloading")
    }

    @Test fun ready() {
        draw(TranscriptUi(model = ModelState.Ready("NB-Whisper small"), estimateMs = 165_000))
        snap("transcript-ready")
    }

    @Test fun running() {
        draw(TranscriptUi(model = ModelState.Ready("NB-Whisper small"), job = JobState.Running(42)))
        snap("transcript-running")
    }

    @Test fun failed() {
        draw(TranscriptUi(model = ModelState.Ready("NB-Whisper small"), job = JobState.Failed("Could not load whisper model")))
        snap("transcript-failed")
    }

    @Test fun done() {
        draw(TranscriptUi(model = ModelState.Ready("NB-Whisper small"), transcript = Transcript(recording.name, facts, lines)))
        snap("transcript-done")
    }

    @Config(qualifiers = "+night")
    @Test fun doneDeckDark() {
        draw(TranscriptUi(model = ModelState.Ready("NB-Whisper small"), transcript = Transcript(recording.name, facts, lines)), LookChoice(Look.DECK))
        snap("transcript-done-deck-dark")
    }

    @Test fun doneMultitrack() {
        draw(TranscriptUi(model = ModelState.Ready("NB-Whisper small"), transcript = Transcript(recording.name, facts, lines)), LookChoice(Look.MULTITRACK))
        snap("transcript-done-multitrack")
    }

    /** One mix on both channels (the Galaxy A24's phone calls): no names. */
    @Test fun doneAsOneMix() {
        val mixed = lines.map { it.copy(speaker = Speaker.NONE) }
        draw(TranscriptUi(model = ModelState.Ready("Whisper small"), transcript = Transcript(recording.name, facts.copy(split = false, modelId = "small-q5_1"), mixed)))
        snap("transcript-done-mixed")
    }

    // The Summary tab.

    private val done = TranscriptUi(
        model = ModelState.Ready("NB-Whisper small"),
        transcript = Transcript(recording.name, facts, lines),
        tab = TranscriptTab.SUMMARY,
    )

    private val summary = CallSummary(
        intent = "Levering flyttet til fredag",
        summary = "Lageret ringte om leveransen: den kommer på fredag mellom ni og elleve, ikke torsdag. Sjåføren ringer en halvtime før.",
        keyPoints = listOf("[0:05] Leveransen kommer fredag mellom ni og elleve, ikke torsdag"),
        decisions = listOf("[0:12] Fredag passer"),
        actionItems = listOf("[0:16] Sjåføren ringer en halvtime før"),
        keyFacts = listOf("Fredag, 9-11"),
    )

    @Test fun summaryNoModel() {
        draw(done.copy(summaryModel = SummaryModelState.Missing(SummaryModel.QWEN3_1_7B_Q4_K_M, 0)))
        snap("summary-no-model")
    }

    @Test fun summaryTooSmallAPhone() {
        draw(done.copy(summaryModel = SummaryModelState.TooSmall(4)))
        snap("summary-phone-too-small")
    }

    @Test fun summaryReady() {
        draw(done.copy(summaryModel = SummaryModelState.Ready(SummaryModel.QWEN3_1_7B_Q4_K_M)))
        snap("summary-ready")
    }

    @Test fun summaryRunning() {
        draw(done.copy(summaryModel = SummaryModelState.Ready(SummaryModel.QWEN3_1_7B_Q4_K_M), summaryJob = JobState.Running(50)))
        snap("summary-running")
    }

    @Test fun summaryDone() {
        draw(done.copy(summaryModel = SummaryModelState.Ready(SummaryModel.QWEN3_1_7B_Q4_K_M), summary = summary, summaryModelId = "qwen3-1.7b-q4_k_m", summaryWallMs = 94_000))
        snap("summary-done")
    }

    @Config(qualifiers = "+night")
    @Test fun summaryDoneDeckDark() {
        draw(done.copy(summaryModel = SummaryModelState.Ready(SummaryModel.QWEN3_1_7B_Q4_K_M), summary = summary, summaryModelId = "qwen3-1.7b-q4_k_m", summaryWallMs = 94_000), LookChoice(Look.DECK))
        snap("summary-done-deck-dark")
    }

    @Test fun summaryNeedsATranscript() {
        draw(done.copy(transcript = null, model = ModelState.Ready("NB-Whisper small")))
        snap("summary-no-transcript")
    }

    @Test fun nothingSaid() {
        draw(TranscriptUi(model = ModelState.Ready("NB-Whisper small"), transcript = Transcript(recording.name, facts, emptyList())))
        snap("transcript-no-speech")
    }
}
