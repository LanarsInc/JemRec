package com.jemcik.jemrec.summary

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.TranscriptionLanguageChoice
import com.jemcik.jemrec.transcripts.Transcript
import com.jemcik.jemrec.transcripts.TranscriptRepository
import com.jemcik.jemrec.transcripts.db.CallSummaryEntry
import java.util.Locale

/** One prompt in, the model's text out: the engine in the app, a stand-in in the tests. */
fun interface SummarySession {
    fun generate(prompt: String, maxTokens: Int, grammar: String?): String
}

/** Loads a model, lends it to [block], frees it. */
fun interface SummaryModelHost {
    suspend fun run(modelPath: String, block: suspend (SummarySession) -> CallSummary?): CallSummary?
}

/** How a summary ended. */
sealed interface SummaryOutcome {
    data class Done(val summary: CallSummary) : SummaryOutcome
    data object NoTranscript : SummaryOutcome
    data object Stopped : SummaryOutcome
    /** Not enough free memory to load the model now; nothing was started. */
    data class NoRoom(val needBytes: Long, val freeBytes: Long) : SummaryOutcome
    data class Failed(val reason: String) : SummaryOutcome
}

/**
 * A recording's summary, from its transcript.
 *
 * CallVault's method (5cfa2c60), on JemRec's storage: the lines cut into
 * chunks the model can hold, each summarised to a JSON document the grammar
 * keeps valid, the parts merged by the model, timestamps it was not given
 * dropped (SummaryCitations), repeats removed (SummaryDedupe). The language is
 * named outright in the prompt (SummaryLanguage) - left to the model, a small
 * one writes English. Lines are given as "You:" and the other person's name
 * when the call's two sides were transcribed apart.
 */
class SummaryRunner(
    private val repository: TranscriptRepository,
    private val settings: TranscribeSettings,
    private val host: SummaryModelHost,
    private val freeMemory: () -> Long,
    private val wasAborted: () -> Boolean = { SummaryEngine.wasAborted() },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val deviceLanguage: () -> String = { Locale.getDefault().language },
) {

    suspend fun run(
        recording: String,
        contact: String?,
        model: SummaryModel,
        modelPath: String,
        shouldStop: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): SummaryOutcome {
        val transcript = repository.load(recording)
        if (transcript == null || transcript.lines.isEmpty()) return SummaryOutcome.NoTranscript

        // Checked before the load, not discovered by Android killing the app halfway.
        val free = freeMemory()
        if (free < model.minFreeBytes) {
            Log.w(TAG, "Not enough free memory for ${model.id}: ${free / 1_000_000} MB free, ${model.minFreeBytes / 1_000_000} MB needed")
            return SummaryOutcome.NoRoom(model.minFreeBytes, free)
        }

        val lines = linesOf(transcript, contact)
        val named = lines.any { it.speaker != null }
        val language = languageOf(transcript, lines)
        val chunks = SummaryChunking.chunk(lines)
        onProgress(0, chunks.size)
        val startedAt = clock()

        val summary = runCatching {
            host.run(modelPath) { session ->
                val parts = mutableListOf<String>()
                chunks.forEachIndexed { index, chunk ->
                    if (shouldStop()) return@run null
                    val prompt = SummaryPrompt.forChunkJson(chunk, language, withTimestamps = true, speakersAreNamed = named)
                    parts += session.generate(promptFor(model, prompt), CHUNK_TOKEN_BUDGET, SummaryGrammar.JSON)
                    onProgress(index + 1, chunks.size)
                }
                if (shouldStop() || wasAborted()) return@run null

                val parsed = parts.mapNotNull(CallSummary::parse)
                if (parsed.size < parts.size) Log.w(TAG, "${parts.size - parsed.size} of ${parts.size} part(s) did not parse")
                val merged = if (parsed.size <= 1) parsed.firstOrNull() else {
                    val document = session.generate(promptFor(model, SummaryPrompt.forMergeJson(parts, language)), MERGE_TOKEN_BUDGET, SummaryGrammar.JSON)
                    CallSummary.parse(document) ?: CallSummary.concatenate(parsed)
                }
                merged?.let { m ->
                    val checked = SummaryCitations.strip(m, lines.map { it.startMs }.toSet(), lines.maxOf { it.endMs })
                    if (checked.removed.isNotEmpty()) Log.w(TAG, "Dropped ${checked.removed.size} timestamp(s) the transcript does not have")
                    SummaryDedupe.apply(SummaryTidy.apply(checked.summary, lines))
                }
            }
        }
        val error = summary.exceptionOrNull()
        if (wasAborted() || shouldStop()) return SummaryOutcome.Stopped
        if (error != null) return SummaryOutcome.Failed(error.message ?: error.javaClass.simpleName)
        val done = summary.getOrNull() ?: return SummaryOutcome.Failed("The model gave nothing that could be read as a summary.")

        val wallMs = clock() - startedAt
        repository.saveSummary(
            CallSummaryEntry(
                recording = recording,
                modelId = model.id,
                language = language,
                json = done.toJson(),
                searchText = done.searchableText(),
                wallMs = wallMs,
                createdAt = now(),
            ),
        )
        Log.i(TAG, "Summarised ${chunks.size} chunk(s) with ${model.id} in ${wallMs / 1000}s")
        return SummaryOutcome.Done(done)
    }

    /** The language to write in: the transcript's own, then Settings', then the text's script, then the phone's. */
    private fun languageOf(transcript: Transcript, lines: List<SummaryLine>): String =
        SummaryLanguage.resolve(
            chosen = null,
            transcriptionSetting = settings.language?.takeIf { it != TranscriptionLanguageChoice.AUTO },
            transcriptLanguage = transcript.facts.language.takeIf { it != TranscriptionLanguageChoice.AUTO },
            transcriptText = lines.joinToString(" ") { it.text },
            deviceLanguage = deviceLanguage(),
        )

    companion object {
        private const val TAG = "JR:Summary"

        /** CallVault's budgets: a chunk's JSON, and the merge of several. */
        const val CHUNK_TOKEN_BUDGET = 1_600
        const val MERGE_TOKEN_BUDGET = 2_000

        /**
         * The transcript as the prompt shows it: "You" for the phone's owner,
         * the contact's name (or "Them") for the other side, nothing when the
         * sides were not told apart.
         */
        fun linesOf(transcript: Transcript, contact: String?): List<SummaryLine> {
            val them = contact?.takeIf { it.isNotBlank() && it.none(Char::isDigit) } ?: "Them"
            return transcript.lines.map { line ->
                SummaryLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    text = line.text,
                    speaker = when (line.speaker) {
                        Speaker.YOU -> "You"
                        Speaker.THEM -> them
                        Speaker.NONE -> null
                    },
                )
            }
        }

        /**
         * Qwen3 thinks aloud first unless told not to, which costs the phone
         * minutes for text nobody reads (CallVault measured a third of Qwen's
         * budget spent so). Its own switch, `/no_think`, turns that off;
         * SummaryText.stripReasoning removes whatever is left.
         */
        fun promptFor(model: SummaryModel, prompt: String): String =
            if (model == SummaryModel.QWEN3_1_7B_Q4_K_M) "$prompt\n/no_think" else prompt

        fun of(context: Context): SummaryRunner {
            val app = context.applicationContext
            return SummaryRunner(
                repository = TranscriptRepository.of(app),
                settings = TranscribeSettings.of(app),
                host = { path, block ->
                    SummaryEngine.withModel(app, path) { session ->
                        block { prompt, maxTokens, grammar -> session.generate(prompt, maxTokens, grammar) }
                    }
                },
                freeMemory = {
                    val info = ActivityManager.MemoryInfo()
                    app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
                    info.availMem
                },
            )
        }
    }
}
