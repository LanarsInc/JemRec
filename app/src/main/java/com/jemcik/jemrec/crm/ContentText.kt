package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.api.Content
import com.jemcik.jemrec.summary.CallSummary
import com.jemcik.jemrec.transcribe.Speaker
import com.jemcik.jemrec.transcribe.TranscriptSegment

/**
 * The transcript and summary as step 2 sends them (§8, research R8), made
 * from what the phone shows without changing it (FR-034). Pure.
 */
object ContentText {

    const val MAX_TRANSCRIPT = 200_000
    const val MAX_SUMMARY = 10_000

    /**
     * One `label: text` line per turn, without times (§8: the label is free
     * text, never parsed). The seller is the phone's side (You), the customer
     * the other (Them). Where both sides are one mix, as on a Galaxy A24's
     * phone calls (ARCHITECTURE "Transcripts"), no line says who: each is
     * "Call", until telling the voices apart is a feature of its own.
     * Consecutive lines of one side are one turn.
     */
    fun transcript(lines: List<TranscriptSegment>): String {
        val turns = mutableListOf<Pair<Speaker, StringBuilder>>()
        for (l in lines) {
            val text = l.text.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty()) continue
            val last = turns.lastOrNull()
            if (last != null && last.first == l.speaker) last.second.append(' ').append(text)
            else turns += l.speaker to StringBuilder(text)
        }
        return turns.joinToString("\n") { (who, text) -> "${label(who)}: $text" }
    }

    private fun label(s: Speaker) = when (s) {
        Speaker.YOU -> "Seller"
        Speaker.THEM -> "Customer"
        Speaker.NONE -> "Call"
    }

    /**
     * Plain text with `• ` bullets; the CRM renders no Markdown (§8). The
     * purpose, the account, then each list under its heading; an empty one is
     * left out.
     */
    fun summary(s: CallSummary): String = buildString {
        s.intent.trim().takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
        append(s.summary.trim())
        for ((heading, items) in listOf("Key points" to s.keyPoints, "Decisions" to s.decisions, "Action items" to s.actionItems, "Key facts" to s.keyFacts)) {
            val kept = items.map(String::trim).filter(String::isNotEmpty)
            if (kept.isEmpty()) continue
            append("\n\n").append(heading).append(':')
            kept.forEach { append("\n• ").append(it) }
        }
    }.trim()

    /** The language as the CRM takes it: BCP 47, Norwegian as Bokmål (FR-037). */
    fun language(code: String?): String {
        val c = code?.trim()?.lowercase().orEmpty()
        return when {
            c == "no" -> "nb"
            c == "auto" -> "und"
            c.length in 2..35 && c.all { it.isLetterOrDigit() || it == '-' } -> c
            else -> "und" // BCP 47's "undetermined": detection gave nothing usable
        }
    }

    /**
     * Cut to [max] UTF-16 units (Kotlin's length, as §8 counts) at a line's
     * end when one is near, else at a code point - never between the two
     * halves of a surrogate pair.
     *
     * @return the text, and whether it was cut
     */
    fun cut(text: String, max: Int): Pair<String, Boolean> {
        if (text.length <= max) return text to false
        val lineEnd = text.lastIndexOf('\n', max)
        if (lineEnd > 0 && lineEnd >= max - LINE_SEARCH) return text.substring(0, lineEnd) to true
        var end = max
        if (Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) to true
    }

    /** How far back a line's end is worth cutting at, rather than mid-line. */
    private const val LINE_SEARCH = 2_000

    fun content(lines: List<TranscriptSegment>, summary: CallSummary, language: String?): Content? {
        val (transcript, truncated) = cut(transcript(lines), MAX_TRANSCRIPT)
        if (transcript.isBlank()) return null
        val (summaryText, _) = cut(summary(summary), MAX_SUMMARY)
        return Content(summaryText, transcript, language(language), truncated)
    }
}
