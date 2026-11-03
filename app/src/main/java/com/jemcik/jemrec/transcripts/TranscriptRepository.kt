package com.jemcik.jemrec.transcripts

import android.content.Context
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscriptSegment
import com.jemcik.jemrec.transcripts.db.CallSummaryEntry
import com.jemcik.jemrec.transcripts.db.RecordingLanguageEntry
import com.jemcik.jemrec.transcripts.db.TranscriptDatabase
import com.jemcik.jemrec.transcripts.db.TranscriptEntry
import com.jemcik.jemrec.transcripts.db.TranscriptSegmentEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** How a transcript was made: what [TranscriptEntry] keeps beside the lines. */
data class TranscriptFacts(
    val modelId: String,
    val language: String,
    val split: Boolean,
    val audioMs: Long,
    val wallMs: Long,
    val loadMs: Long,
    val threads: Int,
    val createdAt: Long,
)

/** A finished transcript: how it was made, and its lines in order. */
data class Transcript(val recording: String, val facts: TranscriptFacts, val lines: List<TranscriptSegment>)

/**
 * Transcripts, summaries and per-recording languages, by recording name.
 *
 * The one way the rest of the app reaches the database, so the rules live in
 * one place: a transcript is written whole or not at all, and deleting a
 * recording's text deletes all of it - lines, summary and language - together.
 */
class TranscriptRepository(private val db: TranscriptDatabase) {

    suspend fun save(recording: String, facts: TranscriptFacts, lines: List<TranscriptSegment>) {
        val entry = TranscriptEntry(
            recording = recording,
            modelId = facts.modelId,
            language = facts.language,
            sides = if (facts.split) SPLIT else MIX,
            audioMs = facts.audioMs,
            wallMs = facts.wallMs,
            loadMs = facts.loadMs,
            threads = facts.threads,
            createdAt = facts.createdAt,
        )
        val segments = lines.filter { it.text.isNotBlank() }.mapIndexed { i, line ->
            TranscriptSegmentEntry(
                recording = recording,
                idx = i,
                startMs = line.startMs,
                endMs = maxOf(line.endMs, line.startMs),
                speaker = line.speaker.key,
                text = line.text.trim(),
            )
        }
        // A new transcript makes the old summary a summary of something else.
        db.summaries().deleteFor(recording)
        db.transcripts().replace(entry, segments)
    }

    suspend fun load(recording: String): Transcript? {
        override?.let { return it(recording) }
        val entry = db.transcripts().transcript(recording) ?: return null
        return Transcript(recording, entry.facts(), db.transcripts().segments(recording).map { it.line() })
    }

    fun observe(recording: String): Flow<Transcript?> =
        override?.let { flowOf(it(recording)) } ?: db.transcripts().observe(recording).combine(db.transcripts().observeSegments(recording)) { entry, segments ->
            entry?.let { Transcript(recording, it.facts(), segments.map { s -> s.line() }) }
        }

    /** The names of every recording with a transcript. */
    fun observeTranscribed(): Flow<Set<String>> = db.transcripts().observeTranscribed().map { it.toSet() }

    /**
     * Recordings whose transcript or summary has what was [typed], each with
     * the words around the first match (marked as SearchMarks says). Empty
     * for nothing searchable, and for a query SQLite still refuses.
     */
    suspend fun search(typed: String): Map<String, String> {
        val query = TranscriptSearch.matchQuery(typed) ?: return emptyMap()
        return runCatching { TranscriptSearch.merge(db.transcripts().search(query), db.summaries().search(query)) }
            .getOrDefault(emptyMap())
    }

    suspend fun saveSummary(entry: CallSummaryEntry) = db.summaries().upsert(entry)

    suspend fun summary(recording: String): CallSummaryEntry? =
        summaryOverride?.let { return it(recording) } ?: db.summaries().summary(recording)

    fun observeSummary(recording: String): Flow<CallSummaryEntry?> =
        summaryOverride?.let { flowOf(it(recording)) } ?: db.summaries().observe(recording)

    suspend fun languageFor(recording: String): String? = db.languages().languageFor(recording)

    suspend fun setLanguage(recording: String, language: String?) {
        if (language == null) db.languages().deleteFor(recording)
        else db.languages().set(RecordingLanguageEntry(recording, language))
    }

    /** Everything derived from [recording]: for a recording deleted for good. */
    suspend fun deleteFor(recording: String) {
        db.summaries().deleteFor(recording)
        db.transcripts().deleteFor(recording)
        db.languages().deleteFor(recording)
    }

    /**
     * Deletes the text of every recording not in [listed] - the recordings and
     * Recently deleted together, as the list last read them - and returns how
     * many went. The one path that covers every way a recording disappears:
     * deleted for good here, emptied by Android from its own trash after its
     * days, or removed with another app. As Waveforms.prune does for levels,
     * and called beside it, under the same guard: never over a list that is
     * not the phone's. A transcript made in the last [graceMs] is kept - its
     * recording may be newer than the list.
     */
    suspend fun prune(listed: Collection<String>, now: Long = System.currentTimeMillis(), graceMs: Long = PRUNE_GRACE_MS): Int {
        val keep = listed.toHashSet()
        val drop = TranscriptPrune.toDrop(db.transcripts().stored(), keep, now, graceMs)
        drop.forEach { deleteFor(it) }
        db.languages().recordings().filter { it !in keep && it !in drop }.forEach { db.languages().deleteFor(it) }
        return drop.size
    }

    private fun TranscriptEntry.facts() = TranscriptFacts(
        modelId = modelId,
        language = language,
        split = sides == SPLIT,
        audioMs = audioMs,
        wallMs = wallMs,
        loadMs = loadMs,
        threads = threads,
        createdAt = createdAt,
    )

    private fun TranscriptSegmentEntry.line() =
        TranscriptSegment(startMs = startMs, endMs = endMs, text = text, speaker = Speaker.of(speaker))

    companion object {
        private const val SPLIT = "split"
        private const val MIX = "mix"
        private const val PRUNE_GRACE_MS = 10 * 60_000L

        fun of(context: Context) = TranscriptRepository(TranscriptDatabase.of(context))

        /**
         * Transcripts that stand in for the database's: the debug build's
         * invented calls (diag/DemoRecordings), so the screenshots show one.
         * A plain nullable, as Recordings.override is, because nothing in a
         * release build can set it.
         */
        @Volatile var override: ((recording: String) -> Transcript?)? = null

        /** The same for summaries. */
        @Volatile var summaryOverride: ((recording: String) -> CallSummaryEntry?)? = null
    }
}
