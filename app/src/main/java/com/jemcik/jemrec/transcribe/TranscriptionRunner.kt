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

/**
 * Each side's likeliest languages, as whisper ranks them, for a call left to
 * detect; an empty list where it could not tell. The engine in the app.
 */
fun interface LanguageDetector {
    suspend fun detect(uri: Uri, modelPath: String, channels: List<Channel>): Map<Channel, List<Pair<String, Double>>>
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
    /** The general model: used as is when the language is pinned, and to detect it when not. */
    val modelId: String,
    val modelPath: String,
    /** An ISO code, or null to detect it (LanguageGuess). */
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
 * the mix once and named nothing; for a call left to detect, each side's
 * language found first and pinned, with the model made for it if there is one
 * (LanguageGuess); one retry when the text comes back in the
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
    /** Nothing detected unless given: the tests that are not about it see the old behaviour. */
    private val detector: LanguageDetector = LanguageDetector { _, _, _ -> emptyMap() },
    /** The model for a detected language, or null to keep the request's. */
    private val modelFor: (String) -> ReadyModel? = { null },
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
        Log.i(TAG, "Transcribing ${request.recording.length}-char name, ${audioMs / 1000}s, ${request.language ?: "auto"}, sides $sides")

        val startedAt = clock()
        TranscriptionInFlight.claim(request.recording)
        var passes = emptyList<Pass>()
        val attempt = try {
            runCatching {
                passes = passes(request, channels)
                if (shouldStop() || wasAborted()) return@runCatching emptyList()
                passes.flatMap { pass ->
                    val prompt = runCatching { TranscriptionPrompt.build(request.contact, pass.language) }.getOrNull()
                    val first = transcriber.transcribe(request.uri, pass.modelPath, pass.language, prompt, pass.channels, DecodeSettings.DEFAULT)
                    if (!WrongScriptRetry.shouldRetry(first, pass.language, audioMs)) first
                    else {
                        Log.w(TAG, "Not in the pinned language's script (${pass.language}); decoding again")
                        WrongScriptRetry.recover(first, pass.language) { decode ->
                            transcriber.transcribe(request.uri, pass.modelPath, pass.language, prompt, pass.channels, decode)
                        }
                    }.also { pass.chars = it.sumOf { line -> line.text.length } }
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
        // Two sides in two languages are one transcript: it is filed under the
        // side that said more, which is also the language its summary is written in.
        val main = passes.maxByOrNull { it.chars } ?: Pass(channels, request.language, request.modelId, request.modelPath)
        withContext(NonCancellable) {
            repository.save(
                request.recording,
                TranscriptFacts(
                    modelId = main.modelId,
                    language = main.language ?: TranscriptionLanguageChoice.AUTO,
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
        // A speed is one model's over the whole call; two models' mixed would be neither's.
        if (passes.size == 1) rememberSpeed(main.modelId, audioMs, wallMs, loadMs)
        Log.i(TAG, "Saved ${lines.size} line(s) in ${wallMs / 1000}s (load ${loadMs}ms)")
        return RunOutcome.Done(lines.size)
    }

    /** One transcription pass: the sides that share a language, and the model for it. */
    private class Pass(val channels: List<Channel>, val language: String?, val modelId: String, val modelPath: String) {
        var chars = 0
    }

    /**
     * How the call is transcribed: in one pass when its language is pinned;
     * otherwise each side's language detected, and the sides that share one
     * transcribed together, with the model made for it when there is one.
     * A side whisper could not tell takes the other side's language where
     * that is likely (LanguageGuess.follow); otherwise whisper detects as it goes.
     */
    private suspend fun passes(request: RunRequest, channels: List<Channel>): List<Pass> {
        val general = Pass(channels, request.language, request.modelId, request.modelPath)
        if (request.language != null) return listOf(general)
        val startedAt = clock()
        val guesses = runCatching { detector.detect(request.uri, request.modelPath, channels) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Could not detect the language: ${it.message}") }
            .getOrDefault(emptyMap())
        val told = channels.associateWith { LanguageGuess.decide(guesses[it].orEmpty()) }
        // A side with no speech to tell by would cost a pass of its own for a word or two:
        // measured on the Galaxy A24, 2026-10-30, a side with one line took 88 s on its own.
        val other = told.values.filterNotNull().distinct().singleOrNull()
        val languages = told.mapValues { (side, language) -> language ?: LanguageGuess.follow(guesses[side].orEmpty(), other) }
        Log.i(TAG, "Detected in ${clock() - startedAt} ms: ${channels.joinToString { "$it ${told[it] ?: "unsure"} ${guesses[it].orEmpty()}" }}")
        return channels.groupBy { languages[it] }.map { (language, sides) ->
            val model = language?.let(modelFor)
            Pass(sides, language, model?.id ?: request.modelId, model?.file?.absolutePath ?: request.modelPath)
        }
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
                // On the model chosen for the call, not the smallest: measured on the Galaxy A24,
                // 2026-10-30, Whisper small heard a short Russian side as Portuguese (0.45) and a
                // Russian speaker mixing in English words as English (0.43), where large-v3-turbo
                // said Russian at 0.78 and 0.97. It costs 23 s a side against small's 11.5.
                detector = LanguageDetector { uri, modelPath, channels ->
                    TranscriptionEngine.detectLanguages(app, uri, modelPath, channels, threadsFor(app))
                },
                modelFor = { language -> ModelChoice.speechModelFor(app, language).second },
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
