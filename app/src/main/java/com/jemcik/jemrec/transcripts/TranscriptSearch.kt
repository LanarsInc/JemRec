package com.jemcik.jemrec.transcripts

import com.jemcik.jemrec.transcripts.db.TranscriptSearchHit

/**
 * What a person typed into the Recordings search, as a full-text query over
 * transcripts and summaries.
 *
 * Words, each matched as the start of a word ("lever" finds "leveransen":
 * FTS4 writes that as "lever*", the asterisk inside the quotes),
 * all of them required, in any order: how a search box is expected to work.
 * Each word is quoted, so nothing typed - a hyphen, a quote, OR, NEAR, an
 * asterisk - is read as FTS syntax; a stray one made SQLite throw instead of
 * finding nothing. Letters and digits of any script (unicode61 indexes them
 * so); everything else separates words.
 */
object TranscriptSearch {

    /** More words than this is a sentence pasted in, not a search; the first ones decide. */
    const val MAX_WORDS = 8

    /** Below this, a word matches too much to mean anything as a prefix ("a", "i"). */
    const val MIN_CHARS = 2

    /** The FTS MATCH expression for [typed], or null when there is nothing to look for. */
    fun matchQuery(typed: String): String? {
        val words = WORD.findAll(typed).map { it.value }.filter { it.length >= MIN_CHARS }.take(MAX_WORDS).toList()
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"$it*\"" }
    }

    /**
     * One snippet per recording, transcripts first (the first line said that
     * matches), then summaries for recordings whose lines did not match.
     */
    fun merge(lines: List<TranscriptSearchHit>, summaries: List<TranscriptSearchHit>): Map<String, String> {
        val hits = LinkedHashMap<String, String>()
        lines.sortedWith(compareBy({ it.recording }, { it.startMs })).forEach { hits.putIfAbsent(it.recording, it.snippet) }
        summaries.forEach { hits.putIfAbsent(it.recording, it.snippet) }
        return hits
    }

    private val WORD = Regex("""[\p{L}\p{N}]+""")
}
