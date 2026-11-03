package com.jemcik.jemrec.transcribe

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The voice engine's bridge on a phone (specs/003-voice-labels, T006): that
 * libvoicejr.so and onnxruntime load, a missing model fails cleanly, and with
 * the models a call mixed to one gives two voices and a unit-length print.
 *
 * Run it as WhisperBridgeTest is, never with Gradle's connected task. The
 * model test needs, in the app's files under `voice-test/`, put there with
 * `run-as` and deleted after: `segmentation.onnx`, `embedding.onnx`, and
 * `call.ogg` - a copy of one of the phone's own two-sided recordings, which
 * the app's decoder mixes to one as it does a phone call. It is skipped
 * without them. Never a real call in the repo.
 */
@RunWith(AndroidJUnit4::class)
class VoiceBridgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "voice-test")

    @Test fun aModelThatIsNotThereFailsCleanly() {
        val missing = File(context.filesDir, "no-such-model.onnx").path
        assertEquals(0L, VoiceNative.open(missing, missing, 4))
        VoiceNative.close(0L)
    }

    @Test fun aMixedCallGivesTwoVoices() {
        val seg = File(dir, "segmentation.onnx")
        val emb = File(dir, "embedding.onnx")
        val call = File(dir, "call.ogg")
        assumeTrue("voice-test files are not on this phone", seg.exists() && emb.exists() && call.exists())
        val decodeStarted = SystemClock.elapsedRealtime()
        val pcm = AudioDecoder.decodeToMono16k(context, Uri.fromFile(call), Channel.MIX)
        Log.i(TAG, "decoded in ${SystemClock.elapsedRealtime() - decodeStarted} ms; " +
            "before the models: RSS ${statusMb("VmRSS:")} MB, peak ${statusMb("VmHWM:")} MB")

        val ptr = VoiceNative.open(seg.path, emb.path, TranscriptionEngine.preferredThreadCount())
        assertTrue(ptr != 0L)
        Log.i(TAG, "models loaded: RSS ${statusMb("VmRSS:")} MB")
        try {
            val started = SystemClock.elapsedRealtime()
            val turns = VoiceNative.turns(ptr, pcm)
            val tookMs = SystemClock.elapsedRealtime() - started
            val audioMs = pcm.size / 16L
            Log.i(TAG, "turns: ${turns.size / 3} for ${audioMs / 1000} s in $tookMs ms " +
                "(RTF %.2f), peak RSS ${statusMb("VmHWM:")} MB".format(tookMs.toDouble() / audioMs))

            assertTrue("no turns", turns.isNotEmpty())
            assertEquals(0, turns.size % 3)
            val voices = (turns.indices step 3).map { turns[it + 2] }.toSet()
            assertEquals(setOf(0, 1), voices)
            val seconds = (turns.indices step 3).groupBy({ turns[it + 2] }, { (turns[it + 1] - turns[it]) / 1000.0 })
                .mapValues { "%.1f s".format(it.value.sum()) }
            Log.i(TAG, "speech per voice: $seconds")

            val print = VoiceNative.embed(ptr, pcm.copyOfRange(0, minOf(pcm.size, 16_000 * 30)))
            Log.i(TAG, "embedding: ${print.size} floats")
            assertTrue("empty embedding", print.isNotEmpty())
            assertTrue(abs(sqrt(print.sumOf { it.toDouble() * it }) - 1.0) < 1e-3)
        } finally {
            VoiceNative.close(ptr)
        }
    }

    private fun statusMb(field: String): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith(field) }
            ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024) ?: -1

    private companion object { const val TAG = "VoiceBridgeTest" }
}
