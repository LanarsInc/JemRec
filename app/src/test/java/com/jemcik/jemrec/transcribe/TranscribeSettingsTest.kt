package com.jemcik.jemrec.transcribe

import android.content.Context
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

/** The defaults data-model.md promises, and that what is set reads back. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class TranscribeSettingsTest {

    private lateinit var settings: TranscribeSettings

    @Before fun fresh() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settings = TranscribeSettings(prefs)
    }

    @Test fun defaults() {
        assertNull(settings.language)
        assertNull(settings.speechModelId)
        assertTrue(settings.languageModels)
        assertEquals(Sides.AUTO, settings.sides)
        assertEquals(TranscribeMode.AFTER_CALL, settings.mode)
        assertTrue(settings.chargingOnly)
        assertEquals(0, settings.threads)
        assertNull(settings.summaryModelId)
        assertTrue(settings.summariseAfterTranscript)
        assertTrue(settings.modelsWifiOnly)
        assertNull(settings.measuredRtf("small-q5_1"))
        assertNull(settings.measuredLoadMs("small-q5_1"))
    }

    @Test fun roundTrips() {
        settings.language = "no"
        settings.sides = Sides.MIX
        settings.mode = TranscribeMode.OVERNIGHT
        settings.threads = 4
        settings.recordRtf("small-q5_1", 0.75)
        settings.recordLoadMs("small-q5_1", 1200)
        assertEquals("no", settings.language)
        assertEquals(Sides.MIX, settings.sides)
        assertEquals(TranscribeMode.OVERNIGHT, settings.mode)
        assertEquals(4, settings.threads)
        assertEquals(0.75, settings.measuredRtf("small-q5_1")!!, 1e-6)
        assertEquals(1200L, settings.measuredLoadMs("small-q5_1"))
    }

    /** "auto" is a choice of its own, not the same as not having chosen. */
    @Test fun autoIsKeptApartFromUnset() {
        settings.language = "auto"
        assertEquals("auto", settings.language)
    }

    /** A value a later version no longer writes reads as the default, never a crash. */
    @Test fun unknownStoredValuesReadAsDefaults() {
        assertEquals(Sides.AUTO, Sides.of("sideways"))
        assertEquals(TranscribeMode.AFTER_CALL, TranscribeMode.of(null))
    }

    @Test fun nonsenseMeasurementsAreNotKept() {
        settings.recordRtf("m", Double.NaN)
        settings.recordRtf("m", -1.0)
        settings.threads = -3
        assertNull(settings.measuredRtf("m"))
        assertEquals(0, settings.threads)
    }
}
