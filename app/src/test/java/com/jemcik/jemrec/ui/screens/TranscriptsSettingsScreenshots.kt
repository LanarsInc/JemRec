package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.transcribe.Sides
import com.jemcik.jemrec.transcribe.model.SpeechModel
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.ModelRow
import com.jemcik.jemrec.ui.SummaryModelRow
import com.jemcik.jemrec.summary.SummaryModel
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.TranscriptsSettingsUi
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Transcripts as drawn: a phone with one model downloaded, one
 * on its way and one half-fetched; a fresh phone with none; and the looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class TranscriptsSettingsScreenshots : ScreenTest() {

    private fun draw(ui: TranscriptsSettingsUi, look: LookChoice = LookChoice()) {
        val state = Fixtures.ready().copy(screen = Screen.TRANSCRIPTS)
        val vm = viewModel(state)
        vm.transcripts.showSettingsForTest(ui)
        show(look = look) { AppScaffold(state, vm) }
        vm.transcripts.showSettingsForTest(ui)
        compose.waitForIdle()
    }

    private val inUse = TranscriptsSettingsUi(
        language = "no",
        models = listOf(
            ModelRow(SpeechModel.NB_SMALL_Q5_0, installed = true, downloading = null, partialBytes = 0, recommended = false),
            ModelRow(SpeechModel.SMALL_Q5_1, installed = true, downloading = null, partialBytes = 0, recommended = true),
            ModelRow(SpeechModel.NB_MEDIUM_Q5_0, installed = false, downloading = 64, partialBytes = 0, recommended = false),
            ModelRow(SpeechModel.TURBO_Q8_0, installed = false, downloading = null, partialBytes = 312_000_000, recommended = false),
        ),
        chosenModel = SpeechModel.SMALL_Q5_1,
        sides = Sides.AUTO,
        // The Galaxy A24's 5.8 GB: the two small ones, not Gemma 4.
        summaryModels = listOf(
            SummaryModelRow(SummaryModel.QWEN3_1_7B_Q4_K_M, fits = true, installed = true, downloading = null, partialBytes = 0),
            SummaryModelRow(SummaryModel.GEMMA_4_E2B_QAT_Q4_K_XL, fits = false, installed = false, downloading = null, partialBytes = 0),
        ),
        summaryModel = SummaryModel.QWEN3_1_7B_Q4_K_M,
    )

    @Config(qualifiers = "+h2000dp")
    @Test fun inUse() {
        draw(inUse)
        snap("transcripts-settings")
    }

    @Config(qualifiers = "+h2000dp")
    @Test fun freshPhone() {
        draw(inUse.copy(language = "en", models = inUse.models.map { it.copy(installed = false, downloading = null, partialBytes = 0) }))
        snap("transcripts-settings-fresh")
    }

    @Config(qualifiers = "+h2000dp-night")
    @Test fun deckDark() {
        draw(inUse, LookChoice(Look.DECK))
        snap("transcripts-settings-deck-dark")
    }

    @Config(qualifiers = "+h2000dp")
    @Test fun multitrack() {
        draw(inUse, LookChoice(Look.MULTITRACK))
        snap("transcripts-settings-multitrack")
    }
}
