package com.jemcik.jemrec.transcripts.export

import com.jemcik.jemrec.summary.CallSummary
import com.jemcik.jemrec.summary.SummaryLine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The five formats, for what goes wrong in them: cue times, who spoke, escaping, the summary. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class TranscriptExportTest {

    private val labels = ExportLabels("Summary", "Notes", "Transcript", "Key points", "Decisions", "To do", "Facts", "Language", "Model")

    private val doc = ExportDocument(
        title = "Priya Raman",
        segments = listOf(
            SummaryLine(1_200, 3_000, "Hei, det er Priya.", "Priya Raman"),
            SummaryLine(3_400, 3_450, "Ja?", "You"),
            SummaryLine(3_661_000, 3_662_500, "Han sa \"fredag\" <ikke torsdag>.", null),
        ),
        summary = CallSummary("Levering", "Leveransen kommer fredag.", listOf("[0:01] Fredag"), emptyList(), emptyList(), emptyList()),
        note = null,
        tags = emptyList(),
        language = "no",
        model = "nb-small-q5_0",
    )

    @Test fun plainTextHasTimesAndSpeakers() {
        val text = TranscriptExport.render(TranscriptFormat.TXT, doc, labels)
        assertTrue(text, text.startsWith("[0:01] Priya Raman: Hei, det er Priya."))
        assertTrue(text, text.contains("[0:03] You: Ja?"))
    }

    @Test fun srtUsesCommasAndHoursAndNumbersItsCues() {
        val srt = TranscriptExport.render(TranscriptFormat.SRT, doc, labels)
        assertTrue(srt, srt.startsWith("1\n00:00:01,200 --> 00:00:03,000\n"))
        assertTrue(srt, srt.contains("01:01:01,000 --> 01:01:02,500"))
    }

    @Test fun vttHasItsHeaderDotsAndEscapes() {
        val vtt = TranscriptExport.render(TranscriptFormat.VTT, doc, labels)
        assertTrue(vtt, vtt.startsWith("WEBVTT"))
        assertTrue(vtt, vtt.contains("00:00:01.200 --> 00:00:03.000"))
        assertTrue(vtt, vtt.contains("&lt;ikke torsdag&gt;"))
    }

    @Test fun markdownCarriesTheSummary() {
        val md = TranscriptExport.render(TranscriptFormat.MARKDOWN, doc, labels)
        assertTrue(md, md.contains("Leveransen kommer fredag."))
        assertTrue(md, md.contains("Key points"))
    }

    @Test fun jsonKeepsQuotesIntact() {
        val json = JSONObject(TranscriptExport.render(TranscriptFormat.JSON, doc, labels))
        assertEquals("Han sa \"fredag\" <ikke torsdag>.", json.getJSONArray("segments").getJSONObject(2).getString("text"))
        assertEquals("You", json.getJSONArray("segments").getJSONObject(1).getString("speaker"))
    }

    @Test fun theFileIsNamedAfterTheRecording() {
        assertEquals("20261029_101354_out.srt", TranscriptExport.fileName(TranscriptFormat.SRT, "20261029_101354_out.ogg"))
    }
}
