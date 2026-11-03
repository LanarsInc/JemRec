package com.jemcik.jemrec.summary

/**
 * Removing what a small model puts in a summary that is not summary.
 *
 * Measured on the Galaxy A24 with Qwen3 1.7B, 2026-10-29 (research.md R14):
 * the Ukrainian sample's "facts" were eight bare timestamps, "[0:00]" to
 * "[0:28]", with no words after them; and in English and Norwegian the "key
 * points" were transcript lines copied out whole, speaker and all
 * ("[0:04] Lars: Ja, jeg har bestillingen her..."). The prompt already asks
 * for the model's own words (CallVault's OWN_WORDS); a 1.7B model does not
 * always do as asked, so this is the part that does not ask, beside
 * SummaryCitations and SummaryDedupe.
 *
 * Dropped, never rewritten: an item gone is a gap the summary's prose still
 * covers, an item rewritten here would be words nobody said.
 */
object SummaryTidy {

    fun apply(summary: CallSummary, lines: List<SummaryLine>): CallSummary {
        val said = lines.map { normal(it.text) }.toSet()
        fun keep(items: List<String>) = items.filter { item ->
            val text = TranscriptTimestamp.parseLeading(item)?.text ?: item
            val body = withoutSpeaker(text, lines)
            body.isNotBlank() && !isOnlyStamp(text) && normal(body) !in said
        }
        return summary.copy(
            keyPoints = keep(summary.keyPoints),
            decisions = keep(summary.decisions),
            actionItems = keep(summary.actionItems),
            keyFacts = keep(summary.keyFacts),
        )
    }

    /** "0:04" or "[0:04]" with nothing else: a time, not a point. */
    private fun isOnlyStamp(text: String) = STAMP_ONLY.matches(text.trim())

    /** "Lars: Ja, jeg ..." without "Lars: ", when Lars is a speaker of this call (or "You"). */
    private fun withoutSpeaker(text: String, lines: List<SummaryLine>): String {
        val trimmed = text.trim()
        val speakers = lines.mapNotNull { it.speaker }.toSet()
        val name = speakers.firstOrNull { trimmed.startsWith("$it:") } ?: return trimmed
        return trimmed.removePrefix("$name:").trim()
    }

    /** Case, spacing and the end's punctuation do not make a line the model's own. */
    private fun normal(text: String) = text.trim().lowercase().replace(SPACES, " ").trimEnd('.', '!', '?', '…', ' ')

    private val STAMP_ONLY = Regex("""\[?\d{1,2}(:\d{2}){1,2}]?""")
    private val SPACES = Regex("""\s+""")
}
