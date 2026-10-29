package com.jemcik.jemrec.transcribe

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.transcribe.model.ModelRepository
import com.jemcik.jemrec.transcribe.model.SpeechModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How many threads whisper should get on this phone: the newest recording
 * transcribed with each count, the time each took. A measurement, written to
 * `threads-benchmark.txt` in the app's external files directory, not a pass
 * or fail. CallVault found every core but two best on its 6+2 OnePlus; a
 * phone with two fast cores and six slow ones may not agree. Nothing of what
 * was said is written, only times. Run with `adb shell am instrument`.
 */
@RunWith(AndroidJUnit4::class)
class ThreadsBenchmark {

    @Test fun measure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = ModelRepository.pathFor(context, SpeechModel.SMALL_Q5_1)
        val recording = Recordings.list(context).maxByOrNull { it.name }
        assumeTrue("needs Whisper small and a recording", model != null && recording != null)
        val report = StringBuilder("threads benchmark, ${android.os.Build.MODEL}, ${recording!!.durationMillis} ms of audio, Whisper small\n")
        for (threads in listOf(2, 4, 6, 8)) {
            val started = SystemClock.elapsedRealtime()
            val lines = TranscriptionEngine.transcribe(
                context, recording.uri, model!!.absolutePath, "ru", null,
                DecodeSettings.DEFAULT, listOf(Channel.LEFT, Channel.RIGHT), threads,
            )
            val ms = SystemClock.elapsedRealtime() - started
            report.append("threads $threads: $ms ms (setup ${TranscriptionEngine.lastSetupMs} ms), ${lines.size} lines\n")
        }
        File(context.getExternalFilesDir(null), "threads-benchmark.txt").writeText(report.toString())
    }
}
