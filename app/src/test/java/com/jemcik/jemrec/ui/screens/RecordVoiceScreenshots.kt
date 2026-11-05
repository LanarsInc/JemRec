package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.SampleUi
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Your voice: before, while recording, too short, and done; in the Deck look, dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class RecordVoiceScreenshots : ScreenTest() {

    private fun draw(sample: SampleUi, look: LookChoice = LookChoice()) {
        val state = Fixtures.ready().copy(screen = Screen.RECORD_VOICE)
        val vm = viewModel(state)
        vm.transcripts.showSampleForTest(sample)
        show(look = look) { AppScaffold(state, vm) }
    }

    @Test fun before() {
        draw(SampleUi.Idle)
        snap("record-voice")
    }

    @Test fun recording() {
        draw(SampleUi.Recording(levelDb = -18.0, elapsedMs = 12_000))
        snap("record-voice-recording")
    }

    @Test fun tooShort() {
        draw(SampleUi.TooShort(speechMs = 11_000))
        snap("record-voice-too-short")
    }

    @Test fun done() {
        draw(SampleUi.Saved)
        snap("record-voice-done")
    }

    @Config(qualifiers = "+night")
    @Test fun deckDark() {
        draw(SampleUi.Recording(levelDb = -24.0, elapsedMs = 20_000), LookChoice(Look.DECK))
        snap("record-voice-deck-dark")
    }
}
