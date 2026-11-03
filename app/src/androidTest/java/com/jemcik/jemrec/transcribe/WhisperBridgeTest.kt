package com.jemcik.jemrec.transcribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jemcik.jemrec.summary.LlamaNative
import com.jemcik.jemrec.transcribe.model.ModelRepository
import com.jemcik.jemrec.transcribe.model.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * The native side, on a phone: what no JVM test can reach. That both
 * libraries load and find their CPU backend, and that whisper takes audio and
 * gives segments back through the bridge - without inventing words in silence.
 *
 * Run it without Gradle's connected task, which uninstalls the app after
 * (CLAUDE.md: never with the recorder up). docs/DEVELOPMENT.md says how.
 * The model tests need Whisper small on the phone and are skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBridgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val libDir = context.applicationInfo.nativeLibraryDir

    @Test fun bothEnginesLoadWithAnArmBackend() {
        assertTrue(WhisperNative.systemInfo(libDir), WhisperNative.systemInfo(libDir).contains("NEON = 1"))
        assertTrue(LlamaNative.systemInfo(libDir), LlamaNative.systemInfo(libDir).contains("NEON = 1"))
    }

    @Test fun aModelThatIsNotThereFailsCleanly() {
        assertEquals(0L, WhisperNative.initContext(context.filesDir.resolve("no-such-model.bin").path, libDir))
    }

    private fun smallModel(): String {
        val file = ModelRepository.pathFor(context, SpeechModel.SMALL_Q5_1)
        assumeTrue("Whisper small is not on this phone", file != null)
        return file!!.path
    }

    private fun run(audio: FloatArray, vad: Boolean): List<String> {
        val ptr = WhisperNative.initContext(smallModel(), libDir)
        assertNotEquals(0L, ptr)
        try {
            val vadPath = if (vad) VadModel.ensureExtracted(context) else null
            WhisperNative.transcribe(ptr, audio, 4, "en", null, vadPath, 1, -1)
            return (0 until WhisperNative.segmentCount(ptr)).map { WhisperNative.segmentText(ptr, it).trim() }.filter { it.isNotEmpty() }
        } finally {
            WhisperNative.freeContext(ptr)
        }
    }

    /** Ten seconds of nothing: with the VAD on there is no speech, so no text. */
    @Test fun silenceGivesNoText() {
        assertEquals(emptyList<String>(), run(FloatArray(16_000 * 10), vad = true))
    }

    /** A steady tone is not speech either. */
    @Test fun aToneGivesNoText() {
        val tone = FloatArray(16_000 * 5) { (0.3 * sin(2 * PI * 440 * it / 16_000)).toFloat() }
        assertEquals(emptyList<String>(), run(tone, vad = true))
    }

    /** Stopped before it starts, whisper returns at once with nothing, and the next run is not stopped too. */
    @Test fun anAbortIsClearedForTheNextRun() {
        val ptr = WhisperNative.initContext(smallModel(), libDir)
        try {
            WhisperNative.requestAbort()
            WhisperNative.transcribe(ptr, FloatArray(16_000 * 3), 4, "en", null, null, 1, -1)
            assertTrue(WhisperNative.progressPercent() in 0..100)
        } finally {
            WhisperNative.freeContext(ptr)
        }
    }
}
