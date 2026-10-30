package com.jemcik.jemrec.transcribe

import com.jemcik.jemrec.transcribe.model.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which speech model a call gets, and which language whisper is told. */
class ModelChoiceTest {

    private val small = SpeechModel.SMALL_Q5_1
    private val turbo = SpeechModel.TURBO_Q8_0
    private val nbSmall = SpeechModel.NB_SMALL_Q5_0
    private val nbMedium = SpeechModel.NB_MEDIUM_Q5_0

    @Test fun norwegianGetsNbWhisperWhenItIsDownloaded() {
        assertEquals(nbSmall, ModelChoice.pick("no", null, true, setOf(nbSmall, small), small))
    }

    @Test fun theLargerNbModelWinsWhenBothAreThere() {
        assertEquals(nbMedium, ModelChoice.pick("no", null, true, setOf(nbSmall, nbMedium), small))
    }

    @Test fun norwegianWithoutNbGetsTheGeneralModel() {
        assertEquals(turbo, ModelChoice.pick("no", turbo.id, true, setOf(turbo), small))
    }

    @Test fun otherLanguagesNeverGetNb() {
        assertEquals(small, ModelChoice.pick("en", null, true, setOf(nbSmall, small), small))
    }

    @Test fun switchedOffLanguageModelsGiveTheChosenOne() {
        assertEquals(turbo, ModelChoice.pick("no", turbo.id, false, setOf(nbSmall, turbo), small))
    }

    /** NB chosen as "the model" is still only for Norwegian: an English call gets the recommendation. */
    @Test fun anNbModelChosenInSettingsIsNotUsedForOtherLanguages() {
        assertEquals(small, ModelChoice.pick("en", nbSmall.id, true, setOf(nbSmall), small))
    }

    @Test fun anUnknownChosenIdFallsBackToTheRecommendation() {
        assertEquals(turbo, ModelChoice.pick("en", "retired-model", true, emptySet(), turbo))
    }

    @Test fun theLanguageIsTheRecordingsThenSettingsThenDetected() {
        assertEquals("uk", ModelChoice.language("uk", "no"))
        assertEquals("no", ModelChoice.language(null, "no"))
        assertNull(ModelChoice.language("auto", "no"))
        assertNull(ModelChoice.language(null, "auto"))
        assertNull(ModelChoice.language(null, null))
    }
}
