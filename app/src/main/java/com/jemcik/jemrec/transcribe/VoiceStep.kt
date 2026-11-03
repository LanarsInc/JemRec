package com.jemcik.jemrec.transcribe

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.jemcik.jemrec.transcribe.model.VoiceModel
import com.jemcik.jemrec.transcripts.VoiceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A one-mix call's lines under their voices, and where the names came from. */
class VoiceResult(
    val lines: List<TranscriptSegment>,
    val source: VoiceSource,
    val modelId: String,
    /** The call's two voices as unit-length embeddings, Voice 1 first. */
    val embeddings: List<FloatArray>,
)

/** Tells a one-mix call's voices apart. VoiceStep in the app; a stand-in in the tests. */
fun interface VoiceTeller {
    /** Null when the voices are not told apart: no model, too little memory, one voice, stopped or failed. */
    suspend fun tell(recording: String, uri: Uri, lines: List<TranscriptSegment>, shouldStop: () -> Boolean): VoiceResult?
}

/**
 * Telling the two voices of a one-mix call apart, after its lines are saved
 * (specs/003-voice-labels, research.md V3-V5).
 *
 * The call is decoded again as one mix, in the chunks its transcription used
 * (ChunkPlan), so memory stays that of one chunk however long the call. In
 * each chunk sherpa-onnx finds who speaks when (two voices at most), and each
 * voice's speech - up to [EMBED_MS] of it - is described by an embedding.
 * [VoiceLink] makes the chunks' voices the call's two, [VoiceSilence] decides
 * whether they may be named at all, and [VoiceSeam] puts each line under its
 * voice, cutting a line where the voice changes.
 *
 * Under the engine lock, like whisper and the summary model: one heavy model
 * in memory at a time (EngineLock). sherpa-onnx cannot be stopped inside a
 * chunk, so Stop is checked between chunks: at the A24's 0.40 of the audio's
 * length (VoiceBridgeTest, 2026-11-03), at most about two minutes after.
 */
object VoiceStep {

    private const val TAG = "JR:Voices"

    /**
     * TitaNet takes at most about 120 s at once (R16); a voice's first minute
     * in a chunk describes it as well as all of it, at half the cost.
     */
    const val EMBED_MS = 60_000L

    /** Below this much of a voice in a chunk, its embedding says little: R16's model wants a few seconds. */
    const val MIN_EMBED_MS = 1_500L

    private val _running = MutableStateFlow<String?>(null)

    /** The recording whose voices are being told apart now, or null. */
    val running: StateFlow<String?> = _running.asStateFlow()

    fun teller(context: Context): VoiceTeller {
        val app = context.applicationContext
        return VoiceTeller { recording, uri, lines, shouldStop -> tell(app, recording, uri, lines, shouldStop) }
    }

    suspend fun tell(
        context: Context,
        recording: String,
        uri: Uri,
        lines: List<TranscriptSegment>,
        shouldStop: () -> Boolean,
    ): VoiceResult? {
        val (segmentation, embedding) = VoiceModel.paths(context) ?: run {
            Log.i(TAG, "No voice model; the lines stay unnamed")
            return null
        }
        return withContext(Dispatchers.Default) {
            EngineLock.mutex.withLock {
                val free = freeMemory(context)
                if (free < VoiceModel.MIN_FREE_BYTES) {
                    Log.w(TAG, "Only ${free / 1_000_000} MB free, ${VoiceModel.MIN_FREE_BYTES / 1_000_000} MB needed; the lines stay unnamed")
                    return@withLock null
                }
                _running.value = recording
                try {
                    run(context, uri, lines, segmentation, embedding, shouldStop)
                } finally {
                    _running.value = null
                }
            }
        }
    }

    private fun run(
        context: Context,
        uri: Uri,
        lines: List<TranscriptSegment>,
        segmentation: File,
        embedding: File,
        shouldStop: () -> Boolean,
    ): VoiceResult? {
        val call = callVoices(context, uri, segmentation, embedding, shouldStop) ?: return null
        if (!VoiceSilence.twoVoices(call)) {
            Log.i(TAG, "Not two voices to name; the lines stay unnamed")
            return null
        }
        val named = VoiceSeam.apply(lines, call.turns, Speaker.VOICE1, Speaker.VOICE2)
        Log.i(TAG, "${lines.size} line(s) became ${named.size}")
        return VoiceResult(named, VoiceSource.VOICES, VoiceModel.ID, call.embeddings)
    }

    /**
     * The call's voices, chunk by chunk and linked; null when stopped or the model would not
     * load. [targetChunkMs] is ChunkPlan's, but for the accuracy test, which cuts a short call
     * into short chunks so the linking is measured on the calls there are.
     */
    internal fun callVoices(
        context: Context,
        uri: Uri,
        segmentation: File,
        embedding: File,
        shouldStop: () -> Boolean,
        targetChunkMs: Long = ChunkPlan.TARGET_CHUNK_MS,
    ): VoiceLink.Call? {
        val startedAt = SystemClock.elapsedRealtime()
        val durationMs = runCatching { AudioDecoder.durationMs(context, uri) }.getOrDefault(0L)
        val plan = ChunkPlan.plan(durationMs, targetMs = targetChunkMs, minTailMs = minOf(ChunkPlan.MIN_TAIL_MS, targetChunkMs / 4))
        val ptr = VoiceNative.open(segmentation.path, embedding.path, TranscriptionEngine.preferredThreadCount())
        if (ptr == 0L) {
            Log.w(TAG, "Could not load the voice model")
            return null
        }
        val chunks = mutableListOf<VoiceLink.Chunk>()
        try {
            for (chunk in plan) {
                if (shouldStop()) {
                    Log.i(TAG, "Stopped; the lines stay unnamed")
                    return null
                }
                val singlePass = plan.size == 1
                val slice = AudioDecoder.decodeRange(
                    context, uri,
                    fromMs = if (singlePass) 0L else chunk.decodeFromMs,
                    toMs = if (singlePass || chunk.endMs <= 0L) Long.MAX_VALUE else chunk.endMs,
                    channel = Channel.MIX,
                    shouldStop = shouldStop,
                )
                if (slice.audio.isEmpty()) continue
                chunks += tellChunk(ptr, slice, keepFromMs = if (singlePass) 0L else chunk.keepFromMs)
            }
        } finally {
            VoiceNative.close(ptr)
        }

        val call = VoiceLink.link(chunks)
        val tookMs = SystemClock.elapsedRealtime() - startedAt
        val cosine = if (call.embeddings.size == 2) "%.2f".format(VoiceLink.cosine(call.embeddings[0], call.embeddings[1])) else "-"
        Log.i(
            TAG,
            "${durationMs / 1000}s in ${plan.size} chunk(s), ${tookMs / 1000}s: " +
                "${call.embeddings.size} voice(s), ${call.speechMs(0) / 1000}s and ${call.speechMs(1) / 1000}s, cosine $cosine, " +
                "peak ${peakRssMb()} MB",
        )
        return call
    }

    /** One chunk's turns in call time, from [keepFromMs] on, and each voice's embedding. */
    private fun tellChunk(ptr: Long, slice: AudioDecoder.DecodedRange, keepFromMs: Long): VoiceLink.Chunk {
        val raw = VoiceNative.turns(ptr, slice.audio)
        val local = (raw.indices step 3).map { VoiceTurn(raw[it].toLong(), raw[it + 1].toLong(), raw[it + 2]) }
        val embeddings = local.map { it.voice }.distinct().mapNotNull { voice ->
            val pcm = speechOf(slice.audio, local.filter { it.voice == voice })
            if (pcm.size < MIN_EMBED_MS * SAMPLES_PER_MS) null
            else VoiceNative.embed(ptr, pcm).takeIf { it.isNotEmpty() }?.let { voice to it }
        }.toMap()
        val turns = local.mapNotNull { t ->
            val start = maxOf(t.startMs + slice.startMs, keepFromMs)
            val end = t.endMs + slice.startMs
            if (end > start) VoiceTurn(start, end, t.voice) else null
        }
        return VoiceLink.Chunk(turns, embeddings)
    }

    /** The samples of [turns], one after the other, up to [EMBED_MS]. */
    private fun speechOf(audio: FloatArray, turns: List<VoiceTurn>): FloatArray {
        val max = (EMBED_MS * SAMPLES_PER_MS).toInt()
        val out = FloatArray(minOf(max, turns.sumOf { it.lengthMs * SAMPLES_PER_MS }.toInt()))
        var n = 0
        for (t in turns) {
            val from = (t.startMs * SAMPLES_PER_MS).toInt().coerceIn(0, audio.size)
            val to = (t.endMs * SAMPLES_PER_MS).toInt().coerceIn(from, audio.size)
            val take = minOf(to - from, out.size - n)
            if (take <= 0) break
            System.arraycopy(audio, from, out, n, take)
            n += take
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun freeMemory(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem
    }

    private fun peakRssMb(): Long =
        runCatching {
            File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong() / 1024
        }.getOrDefault(-1)

    private const val SAMPLES_PER_MS = 16L
}
