package com.jemcik.jemrec.transcribe

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * How right the voices are (specs/003-voice-labels, T028, SC-001): a measurement, written to
 * `voice-accuracy.txt` in the app's external files directory and to logcat, not a pass or fail.
 *
 * Each recording is a messenger call, whose two people are on the two channels. The app's decoder
 * mixes them to one, as a phone call on a Galaxy A24 comes; the voice step tells that mix apart;
 * and the channels say who really spoke - every 20 ms frame where one side is 10 dB louder than the
 * other (R16's truth). "right" is the share of those frames under the right voice (the better of
 * the two ways round), "of" the share of them the voice step put under any voice. Each call is
 * told apart twice: in the app's 5-minute chunks, and in 60 s chunks, so the linking of chunks
 * (VoiceLink) is measured on the calls there are.
 *
 * Needs, in the app's files under `voice-test/` (put there with `run-as`, deleted after):
 * `segmentation.onnx`, `embedding.onnx`, and one or more `*.ogg` copies of the phone's own
 * two-sided recordings. Never a real call in the repo. Run with `am instrument`, as
 * VoiceBridgeTest.
 */
@RunWith(AndroidJUnit4::class)
class VoiceAccuracyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "voice-test")

    @Test fun measure() {
        val seg = File(dir, "segmentation.onnx")
        val emb = File(dir, "embedding.onnx")
        val calls = dir.listFiles { f -> f.name.endsWith(".ogg") }.orEmpty().sortedBy { it.name }
        assumeTrue("voice-test files are not on this phone", seg.exists() && emb.exists() && calls.isNotEmpty())

        val report = StringBuilder("call\tchunks\tlength s\tvoice 1 s\tvoice 2 s\tcosine\ttwo voices\ttruth s\tright\tof\ttook s\n")
        for (call in calls) {
            val uri = Uri.fromFile(call)
            val truth = truth(uri)
            for (chunkMs in listOf(ChunkPlan.TARGET_CHUNK_MS, 60_000L)) {
                val started = SystemClock.elapsedRealtime()
                val voices = VoiceStep.callVoices(context, uri, seg, emb, { false }, targetChunkMs = chunkMs) ?: continue
                val took = (SystemClock.elapsedRealtime() - started) / 1000.0
                val (right, of) = score(truth, voices.turns)
                val cosine = if (voices.embeddings.size == 2) VoiceLink.cosine(voices.embeddings[0], voices.embeddings[1]) else Double.NaN
                val line = listOf(
                    call.name, if (chunkMs == ChunkPlan.TARGET_CHUNK_MS) "5 min" else "60 s",
                    "%.0f".format(truth.size * FRAME_MS / 1000.0),
                    "%.1f".format(voices.speechMs(0) / 1000.0), "%.1f".format(voices.speechMs(1) / 1000.0),
                    "%.2f".format(cosine), VoiceSilence.twoVoices(voices).toString(),
                    "%.1f".format(truth.count { it >= 0 } * FRAME_MS / 1000.0),
                    "%.3f".format(right), "%.3f".format(of), "%.0f".format(took),
                ).joinToString("\t")
                Log.i(TAG, line)
                report.appendLine(line)
            }
        }
        File(context.getExternalFilesDir(null), "voice-accuracy.txt").writeText(report.toString())
    }

    /** Per 20 ms frame: 0 when the left side leads by 10 dB, 1 the right, -1 neither or silence. */
    private fun truth(uri: Uri): IntArray {
        val left = levels(AudioDecoder.decodeToMono16k(context, uri, Channel.LEFT))
        val right = levels(AudioDecoder.decodeToMono16k(context, uri, Channel.RIGHT))
        return IntArray(minOf(left.size, right.size)) { i ->
            when {
                maxOf(left[i], right[i]) < SOUND_DB -> -1
                left[i] - right[i] >= LEAD_DB -> 0
                right[i] - left[i] >= LEAD_DB -> 1
                else -> -1
            }
        }
    }

    private fun levels(pcm: FloatArray): DoubleArray = DoubleArray(pcm.size / FRAME) { f ->
        var sum = 0.0
        for (i in f * FRAME until (f + 1) * FRAME) sum += pcm[i].toDouble() * pcm[i]
        20 * log10(sqrt(sum / FRAME).coerceAtLeast(1e-9))
    }

    /** The share of truth frames under the right voice (better way round), and the share under any. */
    private fun score(truth: IntArray, turns: List<VoiceTurn>): Pair<Double, Double> {
        val said = IntArray(truth.size) { -1 }
        for (t in turns) {
            val from = (t.startMs / FRAME_MS).toInt().coerceIn(0, said.size)
            val to = (t.endMs / FRAME_MS).toInt().coerceIn(from, said.size)
            for (i in from until to) said[i] = t.voice
        }
        var straight = 0
        var crossed = 0
        var covered = 0
        var total = 0
        for (i in truth.indices) {
            if (truth[i] < 0) continue
            total++
            if (said[i] < 0) continue
            covered++
            if (said[i] == truth[i]) straight++ else crossed++
        }
        if (covered == 0) return 0.0 to 0.0
        return maxOf(straight, crossed).toDouble() / covered to covered.toDouble() / total
    }

    private companion object {
        const val TAG = "VoiceAccuracy"
        const val FRAME = 320
        const val FRAME_MS = 20L
        const val LEAD_DB = 10.0
        const val SOUND_DB = -45.0
    }
}
