package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.capture.Waveforms
import com.jemcik.jemrec.transcripts.db.SearchMarks
import com.jemcik.jemrec.ui.AnswerUi
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.RecordingsNarrowing
import com.jemcik.jemrec.ui.TextFacts
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The Recordings list found by words said in the calls: each row with the
 * words around the match under its details; and the Transcribed chip, offered
 * where some recordings have a transcript and lit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class SearchTextScreenshots : ScreenTest() {

    @Before fun rowWaveforms() {
        Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
    }

    @After fun noRowWaveforms() {
        Waveforms.override = null
    }

    private val recordings = Fixtures.recordings()
    private fun mark(word: String) = "${SearchMarks.START}$word${SearchMarks.END}"

    private val text = TextFacts(
        transcribed = setOf(recordings[0].name, recordings[1].name, recordings[3].name),
        hits = mapOf(
            recordings[1].name to "…the quote is ready, and the ${mark("delivery")} can be on Friday if you confirm today…",
            recordings[3].name to "…ja, ${mark("delivery")} is moved to Thursday morning…",
        ),
    )

    private fun draw(state: UiState, look: LookChoice = LookChoice()) {
        val vm = viewModel(state, AnswerUi(enabled = true))
        show(look = look) { AppScaffold(state, vm) }
    }

    @Test fun foundBySomethingSaid() {
        draw(Fixtures.ready().copy(recordingsNarrowing = RecordingsNarrowing(searchOpen = true, query = "delivery"), text = text))
        snap("search-transcripts")
    }

    @Config(qualifiers = "+night")
    @Test fun foundBySomethingSaidDeckDark() {
        draw(Fixtures.ready().copy(recordingsNarrowing = RecordingsNarrowing(searchOpen = true, query = "delivery"), text = text), LookChoice(Look.DECK))
        snap("search-transcripts-deck-dark")
    }

    @Test fun onlyTranscribed() {
        draw(Fixtures.ready().copy(recordingsNarrowing = RecordingsNarrowing(transcribed = true), text = text))
        snap("transcribed-chip")
    }
}
