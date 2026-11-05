package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Which seconds are read for the language, and which answers are pinned. */
class LanguageGuessTest {

    private val rate = 16_000

    /** [loud] seconds of a tone at about -20 dBFS where [speech] says so, silence elsewhere. */
    private fun audio(vararg speech: Boolean) = FloatArray(speech.size * rate) { i ->
        if (speech[i / rate]) (0.14 * sin(2 * PI * 200 * i / rate)).toFloat() else 0f
    }

    @Test fun onlyTheSecondsWithSpeechAreKept() {
        val out = LanguageGuess.speech(audio(false, true, false, true, true, false))!!
        assertEquals(3 * rate, out.size)
    }

    @Test fun atMostThirtySecondsAreKept() {
        assertEquals(30 * rate, LanguageGuess.speech(audio(*BooleanArray(90) { true }))!!.size)
    }

    /** LANARS's 274 s call opened with one Russian sentence: a long call is read at three places. */
    @Test fun aLongCallIsReadAtItsStartMiddleAndEnd() {
        assertEquals(listOf(0L until 60_000L), LanguageGuess.windows(90_000))
        assertEquals(listOf(0L until 60_000L), LanguageGuess.windows(LanguageGuess.ACROSS_AFTER_MS))
        assertEquals(
            listOf(0L until 60_000L, 122_000L until 152_000L, 244_000L until 274_000L),
            LanguageGuess.windows(274_000),
        )
    }

    @Test fun threeStretchesFillOneWindowEvenly() {
        val out = LanguageGuess.speechAcross(listOf(audio(*BooleanArray(60) { true }), audio(*BooleanArray(60) { true }), audio(*BooleanArray(60) { true })))!!
        assertEquals(30 * rate, out.size)
        // A stretch with no speech gives none; the others still count.
        val two = LanguageGuess.speechAcross(listOf(audio(*BooleanArray(60) { true }), FloatArray(60 * rate), audio(*BooleanArray(60) { true })))!!
        assertEquals(20 * rate, two.size)
        // One stretch is read as before.
        assertEquals(30 * rate, LanguageGuess.speechAcross(listOf(audio(*BooleanArray(90) { true })))!!.size)
    }

    @Test fun tooLittleSpeechTellsNothing() {
        assertNull(LanguageGuess.speech(audio(true, true, false, false)))
        assertNull(LanguageGuess.speech(FloatArray(10 * rate)))
    }

    @Test fun whispersAnswerIsRead() {
        assertEquals(listOf("no" to 0.812, "da" to 0.101), LanguageGuess.parse("no:0.812 da:0.101"))
        assertEquals(emptyList<Pair<String, Double>>(), LanguageGuess.parse(""))
    }

    @Test fun aSureAnswerInAnOfferedLanguageIsPinned() {
        assertEquals("ru", LanguageGuess.decide(listOf("ru" to 0.97, "uk" to 0.02)))
        assertEquals("no", LanguageGuess.decide(listOf("nn" to 0.7)))
    }

    @Test fun aSideNotToldFollowsTheOtherOnlyWhenItHadNoSpeech() {
        assertEquals("ru", LanguageGuess.follow(emptyList(), "ru"))
        // English news under a Russian speaker, as large-v3-turbo heard it on the A24.
        assertNull(LanguageGuess.follow(listOf("es" to 0.28, "pt" to 0.26, "ru" to 0.24), "ru"))
        assertNull(LanguageGuess.follow(emptyList(), null))
    }

    @Test fun anUnsureOrUnofferedAnswerIsLeftToWhisper() {
        assertNull(LanguageGuess.decide(listOf("no" to 0.4, "da" to 0.35)))
        assertNull(LanguageGuess.decide(listOf("sv" to 0.9)))
        assertNull(LanguageGuess.decide(emptyList()))
    }
}
