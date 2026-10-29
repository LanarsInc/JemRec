package com.jemcik.jemrec.transcribe

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.jemcik.jemrec.capture.PcmDecoder
import com.jemcik.jemrec.transcribe.model.SpeechModel
import com.jemcik.jemrec.transcripts.TranscriptFacts
import com.jemcik.jemrec.transcripts.TranscriptRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.ShortBuffer

/** Turns audio into lines. The engine in the app; a stand-in in the tests. */
fun interface Transcriber {
    suspend fun transcribe(
        uri: Uri,
        modelPath: String,
        language: String?,
        prompt: String?,
        channels: List<Channel>,
        settings: DecodeSettings,
    ): List<TranscriptSegment>
}

/** How one recording's transcription ended. */
sealed interface RunOutcome {
    /** Saved; [lines] may be 0 when no speech was found, which is said as such. */
    data class Done(val lines: Int) : RunOutcome
    /** Stopped by the user or by Android; nothing saved, nothing half-saved. */
    data object Stopped : RunOutcome
    data class Failed(val reason: String) : RunOutcome
}

/** What a transcription is asked to do. */
data class RunRequest(
    val recording: String,
    val uri: Uri,
    val modelId: String,
    val modelPath: String,
    /** An ISO code, or null to detect it. */
    val language: String?,
    /** The contact's name, so whisper spells it right (TranscriptionPrompt); null for none. */
    val contact: String?,
    val sides: Sides,
    val threads: Int,
)

/**
 * One recording, from audio to saved lines.
 *
 * In order: how long the call is; whether its two channels are its two sides
 * (ChannelSides) - then each side is transcribed alone and its lines named, or
 * the mix once and named nothing; one retry when the text comes back in the
 * wrong script for the pinned language (CallVault's WrongScriptRetry); the
 * sides merged in time; saved whole; and this phone's speed for the model
 * remembered, so the next estimate is this phone's and not someone else's.
 *
 * Written for JemRec after CallVault's runner (5cfa2c60), which also handles
 * imported audio, Drive and sidecar files that JemRec does not have.
 */
class TranscriptionRunner(
    private val repository: TranscriptRepository,
    private val settings: TranscribeSettings,
    private val transcriber: Transcriber,
    /** 20 ms levels of each channel over the first minute; null when they cannot be read. */
    private val sideLevels: suspend (Uri) -> Pair<DoubleArray, DoubleArray>?,
    /** The same over the whole call, for QuietSide; null when they cannot be read. */
    private val callLevels: suspend (Uri) -> Pair<DoubleArray, DoubleArray>? = { null },
    private val durationMs: (Uri) -> Long,
    private val wasAborted: () -> Boolean = { TranscriptionEngine.wasAborted() },
    private val setupMs: () -> Long = { TranscriptionEngine.lastSetupMs },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun run(request: RunRequest, shouldStop: () -> Boolean = { false }): RunOutcome {
        val audioMs = durationMs(request.uri)
        val levels = if (request.sides == Sides.AUTO) runCatching { sideLevels(request.uri) }.getOrNull() else null
        val sides = if (levels == null) {
            if (request.sides == Sides.AUTO) Sides.MIX else request.sides
        } else {
            ChannelSides.decide(levels.first, levels.second, request.sides)
        }
        val channels = if (sides == Sides.SPLIT) listOf(Channel.LEFT, Channel.RIGHT) else listOf(Channel.MIX)
        val prompt = runCatching { TranscriptionPrompt.build(request.contact, request.language) }.getOrNull()
        Log.i(TAG, "Transcribing ${request.recording.length}-char name, ${audioMs / 1000}s, ${request.language ?: "auto"}, sides $sides")

        val startedAt = clock()
        TranscriptionInFlight.claim(request.recording)
        val attempt = try {
            runCatching {
                val first = transcriber.transcribe(request.uri, request.modelPath, request.language, prompt, channels, DecodeSettings.DEFAULT)
                if (!WrongScriptRetry.shouldRetry(first, request.language, audioMs)) first
                else {
                    Log.w(TAG, "Not in the pinned language's script (${request.language}); decoding again")
                    WrongScriptRetry.recover(first, request.language) { decode ->
                        transcriber.transcribe(request.uri, request.modelPath, request.language, prompt, channels, decode)
                    }
                }
            }
        } finally {
            TranscriptionInFlight.release(request.recording)
        }

        val error = attempt.exceptionOrNull()
        if (wasAborted() || shouldStop() || error is CancellationException) {
            Log.i(TAG, "Stopped; nothing saved")
            if (error is CancellationException) throw error
            return RunOutcome.Stopped
        }
        if (error != null) {
            Log.w(TAG, "Transcription failed: ${error.message}")
            return RunOutcome.Failed(error.message ?: error.javaClass.simpleName)
        }

        val merged = LineCleanup.apply(TurnMerge.merge(attempt.getOrThrow()))
        // A split call's lines are checked against the levels of the whole call:
        // whisper writes words over a quiet channel (QuietSide).
        val lines = if (sides != Sides.SPLIT) merged else {
            val whole = runCatching { callLevels(request.uri) }.getOrNull()
            if (whole == null) merged else merged.filter { line ->
                val (l, r) = QuietSide.peaks(line, whole.first, whole.second)
                val phantom = QuietSide.isPhantom(line, whole.first, whole.second)
                Log.i(TAG, "line at ${line.startMs}ms ${line.speaker}: left %.1f dB, right %.1f dB%s".format(l, r, if (phantom) " - dropped, its side was quiet" else ""))
                !phantom
            }
        }
        val wallMs = clock() - startedAt
        val loadMs = setupMs().coerceIn(0L, wallMs)
        withContext(NonCancellable) {
            repository.save(
                request.recording,
                TranscriptFacts(
                    modelId = request.modelId,
                    language = request.language ?: TranscriptionLanguageChoice.AUTO,
                    split = sides == Sides.SPLIT,
                    audioMs = audioMs,
                    wallMs = wallMs,
                    loadMs = loadMs,
                    threads = request.threads,
                    createdAt = now(),
                ),
                lines,
            )
        }
        rememberSpeed(request.modelId, audioMs, wallMs, loadMs)
        Log.i(TAG, "Saved ${lines.size} line(s) in ${wallMs / 1000}s (load ${loadMs}ms)")
        return RunOutcome.Done(lines.size)
    }

    /**
     * This phone's real-time factor and load time for the model, blended
     * with what it measured before (TranscriptionEstimate). The load is kept
     * apart from the work: it does not shrink with a shorter call.
     */
    private fun rememberSpeed(modelId: String, audioMs: Long, wallMs: Long, loadMs: Long) {
        val measured = TranscriptionEstimate.measure(audioMs, wallMs - loadMs) ?: return
        val fallback = SpeechModel.fromId(modelId)
        settings.recordRtf(modelId, TranscriptionEstimate.blend(settings.measuredRtf(modelId), measured, fallback?.realTimeFactor ?: TranscriptionEstimate.DEFAULT_RTF))
        if (loadMs > 0) {
            settings.recordLoadMs(modelId, TranscriptionEstimate.blendLoadMs(settings.measuredLoadMs(modelId), loadMs, fallback?.seedLoadMs ?: loadMs))
        }
    }

    companion object {
        private const val TAG = "JR:Transcribe"

        /** The runner the app uses: the engine, the database, and the file's own levels. */
        fun of(context: Context): TranscriptionRunner {
            val app = context.applicationContext
            return TranscriptionRunner(
                repository = TranscriptRepository.of(app),
                settings = TranscribeSettings.of(app),
                transcriber = Transcriber { uri, modelPath, language, prompt, channels, decode ->
                    TranscriptionEngine.transcribe(app, uri, modelPath, language, prompt, decode, channels, threadsFor(app))
                },
                sideLevels = { uri -> readSideLevels(app, uri, ChannelSides.LOOK_MS) },
                callLevels = { uri -> readSideLevels(app, uri, Long.MAX_VALUE / 1000) },
                durationMs = { uri -> AudioDecoder.durationMs(app, uri) },
            )
        }

        /** The user's choice, or the engine's policy when they have made none. */
        fun threadsFor(context: Context): Int =
            TranscribeSettings.of(context).threads.takeIf { it > 0 } ?: TranscriptionEngine.preferredThreadCount()

        /**
         * Levels per channel for the first [lookMs], read with the app's own
         * decoder. Seconds of work, against minutes of transcription.
         */
        private fun readSideLevels(context: Context, uri: Uri, lookMs: Long): Pair<DoubleArray, DoubleArray>? {
            var rate = 48_000
            var channels = 2
            val left = ArrayList<Double>()
            val right = ArrayList<Double>()
            PcmDecoder.decode(
                context, uri,
                onFormat = { r, c -> rate = r; channels = c },
                onPcm = { pcm: ShortBuffer, _ ->
                    val chunk = ShortArray(pcm.remaining()).also { pcm.get(it) }
                    val (l, r) = ChannelSides.levels(chunk, chunk.size, channels, rate)
                    l.forEach { left += it }
                    r.forEach { right += it }
                    left.size.toLong() * ChannelSides.FRAME_MS < lookMs
                },
                untilUs = if (lookMs == Long.MAX_VALUE / 1000) Long.MAX_VALUE else lookMs * 1000,
            )
            if (left.isEmpty()) return null
            return left.toDoubleArray() to right.toDoubleArray()
        }
    }
}
