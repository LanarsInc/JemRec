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
        // The owner's voice, when their print picks it out clearly (VoiceNames); Voice 1 and 2 otherwise.
        val print = OwnerPrint.store(context).read()
        val (first, second) = VoiceNames.decide(call.embeddings, print?.vector)
        val byPrint = first == Speaker.YOU || second == Speaker.YOU
        Log.i(TAG, "Print ${print?.source ?: "none"}: ${if (byPrint) "the owner found" else "the voices stay unnamed"}" +
            (print?.let { p -> call.embeddings.joinToString(prefix = " (", postfix = ")") { "%.2f".format(VoiceLink.cosine(it, p.vector)) } } ?: ""))
        val named = VoiceSeam.apply(lines, call.turns, first, second)
        Log.i(TAG, "${lines.size} line(s) became ${named.size}")
        return VoiceResult(named, if (byPrint) VoiceSource.PRINT else VoiceSource.VOICES, VoiceModel.ID, call.embeddings)
    }

    /**
     * A messenger call's left side offered to the owner's print (OwnerPrintFromCalls), after the
     * call is transcribed apart: [lines] named You and Them by the channels, [sideCorrelation] the
     * two sides' 20 ms level correlation over the first minute. Only the first chunk is listened to:
     * a few windows of the owner's speech say who they are, and a long call costs no more.
     */
    suspend fun learnFromCall(context: Context, uri: Uri, lines: List<TranscriptSegment>, sideCorrelation: Double) {
        val store = OwnerPrint.store(context)
        if (store.read()?.source == PrintSource.SAMPLE) return
        val (segmentation, embedding) = VoiceModel.paths(context) ?: return
        val toMs = ChunkPlan.TARGET_CHUNK_MS
        val yours = lines.filter { it.speaker == Speaker.YOU && it.startMs < toMs }
        val theirs = lines.filter { it.speaker == Speaker.THEM && it.startMs < toMs }
        val leftMs = yours.sumOf { minOf(it.endMs, toMs) - it.startMs }
        if (leftMs < OwnerPrintFromCalls.MIN_SPEECH_MS) return
        withContext(Dispatchers.Default) {
            EngineLock.mutex.withLock {
                if (freeMemory(context) < VoiceModel.MIN_FREE_BYTES) return@withLock
                val ptr = VoiceNative.open(segmentation.path, embedding.path, TranscriptionEngine.preferredThreadCount())
                if (ptr == 0L) return@withLock
                try {
                    val left = AudioDecoder.decodeRange(context, uri, 0L, toMs, Channel.LEFT).audio
                    val right = AudioDecoder.decodeRange(context, uri, 0L, toMs, Channel.RIGHT).audio
                    val speech = speechOf(left, yours.map { VoiceTurn(it.startMs, it.endMs, 0) }, Long.MAX_VALUE)
                    val window = (WINDOW_MS * SAMPLES_PER_MS).toInt()
                    val windows = (0 until minOf(MAX_WINDOWS, speech.size / window)).mapNotNull { i ->
                        VoiceNative.embed(ptr, speech.copyOfRange(i * window, (i + 1) * window)).takeIf { it.isNotEmpty() }
                    }
                    val other = speechOf(right, theirs.map { VoiceTurn(it.startMs, it.endMs, 0) }, EMBED_MS)
                        .takeIf { it.size >= MIN_EMBED_MS * SAMPLES_PER_MS }
                        ?.let { VoiceNative.embed(ptr, it).takeIf { e -> e.isNotEmpty() } }
                    val offered = OwnerPrintFromCalls.offer(
                        OwnerPrintFromCalls.Call(leftMs, speakerphone = null, sideCorrelation, windows, other),
                    )
                    if (offered == null) {
                        Log.i(TAG, "This call's left side is not plainly one voice; the print is unchanged")
                    } else {
                        val kept = store.addCall(offered, System.currentTimeMillis())
                        Log.i(TAG, "The print now comes from ${kept?.callCount ?: 0} call(s)")
                    }
                } finally {
                    VoiceNative.close(ptr)
                }
            }
        }
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
            val pcm = speechOf(slice.audio, local.filter { it.voice == voice }, EMBED_MS)
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

    /** The samples of [turns], one after the other, up to [maxMs]. */
    private fun speechOf(audio: FloatArray, turns: List<VoiceTurn>, maxMs: Long): FloatArray {
        val max = (minOf(maxMs, Int.MAX_VALUE / SAMPLES_PER_MS) * SAMPLES_PER_MS).toInt()
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

    /** The owner's speech in a messenger call is described in windows of this much, to see it is one voice. */
    private const val WINDOW_MS = 10_000L
    private const val MAX_WINDOWS = 4
}
