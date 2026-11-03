package com.jemcik.jemrec.summary

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jemcik.jemrec.transcribe.model.ModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every summary model on the phone, on every sample, through the prompt,
 * grammar and checks the app runs - and what it wrote beside what is true,
 * for a person to read for anything invented. A measurement, not a pass or
 * fail: the report says the time, the memory and the text; the judgement of
 * what was invented is made by reading it (research.md R14).
 *
 * Writes `summary-faithfulness.txt` to the app's external files directory
 * (`adb shell cat /sdcard/Android/data/com.jemcik.jemrec/files/...`).
 * Run with `adb shell am instrument`, never Gradle's connected task, which
 * uninstalls the app and the models with it.
 */
@RunWith(AndroidJUnit4::class)
class SummaryFaithfulnessTest {

    @Test fun measure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = SummaryModel.entries.mapNotNull { m -> ModelRepository.pathFor(context, m)?.let { m to it } }
        assumeTrue("no summary model on this phone", models.isNotEmpty())

        val report = StringBuilder()
        report.appendLine("JemRec summary faithfulness, ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        report.appendLine("llama: ${LlamaNative.systemInfo(context.applicationInfo.nativeLibraryDir)}")
        for ((model, file) in models) for (sample in SummarySamples.all) {
            report.appendLine()
            report.appendLine("=== ${model.id} / ${sample.name} (${sample.language}) ===")
            val lines = sample.asLines()
            val started = SystemClock.elapsedRealtime()
            var pss = 0
            val result = runCatching {
                SummaryEngine.withModel(context, file.absolutePath) { session ->
                    val prompt = SummaryRunner.promptFor(model, SummaryPrompt.forChunkJson(lines, sample.language, withTimestamps = true, speakersAreNamed = true))
                    val raw = session.generate(prompt, SummaryRunner.CHUNK_TOKEN_BUDGET, SummaryGrammar.JSON)
                    pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss / 1024
                    raw
                }
            }
            val ms = SystemClock.elapsedRealtime() - started
            report.appendLine("time: $ms ms (load included), pss while loaded: $pss MB")
            result.onFailure { report.appendLine("FAILED: ${it.message}") }
            result.onSuccess { raw ->
                val parsed = CallSummary.parse(raw)
                if (parsed == null) report.appendLine("DID NOT PARSE:\n$raw")
                else {
                    val checked = SummaryDedupe.apply(SummaryTidy.apply(SummaryCitations.strip(parsed, lines.map { it.startMs }.toSet(), lines.last().endMs).summary, lines))
                    report.appendLine("intent: ${checked.intent}")
                    report.appendLine("summary: ${checked.summary}")
                    checked.keyPoints.forEach { report.appendLine("  point: $it") }
                    checked.decisions.forEach { report.appendLine("  decision: $it") }
                    checked.actionItems.forEach { report.appendLine("  to do: $it") }
                    checked.keyFacts.forEach { report.appendLine("  fact: $it") }
                }
            }
            report.appendLine("--- true in this call ---")
            sample.facts.forEach { report.appendLine("  - $it") }
        }
        File(context.getExternalFilesDir(null), "summary-faithfulness.txt").writeText(report.toString())
    }
}
