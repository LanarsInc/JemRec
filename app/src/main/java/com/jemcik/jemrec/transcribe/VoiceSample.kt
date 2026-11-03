package com.jemcik.jemrec.transcribe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.jemcik.jemrec.transcribe.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The owner reads a short text aloud once, and their voice print is made from
 * it (specs/003-voice-labels, research.md V7, decided 2026-11-03: a recorded
 * sample first). The cleanest source there is: one person, their own
 * microphone, no call in between.
 *
 * The sound is held in memory only, while it is recorded and described;
 * then it is dropped. Only the print - numbers, not sound - is kept
 * (OwnerPrint). Nothing is written to a file and nothing leaves the phone.
 */
object VoiceSample {

    private const val TAG = "JR:Voices"

    /** How long the owner reads for. */
    const val LENGTH_MS = 30_000L

    /** Under this much speech in it, the sample is read again: a print from less says little. */
    const val MIN_SPEECH_MS = 20_000L

    /** A 20 ms frame quieter than this is a pause, not speech (ChannelSides' line for silence). */
    const val SPEECH_DB = ChannelSides.SOUND_DB

    const val RATE = 16_000
    private const val FRAME = RATE / 50

    /** Only the frames with speech in them, one after the other. Plain arithmetic, tested off the phone. */
    fun speechOnly(pcm: FloatArray): FloatArray {
        val out = FloatArray(pcm.size)
        var n = 0
        for (f in 0 until pcm.size / FRAME) {
            if (levelDb(pcm, f * FRAME, FRAME) < SPEECH_DB) continue
            System.arraycopy(pcm, f * FRAME, out, n, FRAME)
            n += FRAME
        }
        return out.copyOf(n)
    }

    fun speechMs(speech: FloatArray): Long = speech.size * 1000L / RATE

    /** dBFS of [length] samples from [from]. */
    fun levelDb(pcm: FloatArray, from: Int, length: Int): Double {
        var sum = 0.0
        for (i in from until from + length) sum += pcm[i].toDouble() * pcm[i]
        return 20 * log10(sqrt(sum / length).coerceAtLeast(1e-9))
    }

    /**
     * [LENGTH_MS] from the microphone, or less when [shouldStop] says so, as 16 kHz mono float.
     * [onLevel] hears each 20 ms frame's level and how far along it is. Null without the
     * microphone permission or when Android will not record.
     */
    suspend fun record(context: Context, onLevel: (Double, Long) -> Unit, shouldStop: () -> Boolean): FloatArray? =
        withContext(Dispatchers.IO) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@withContext null
            }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, FRAME * 4 * 8),
                )
            }.getOrNull()
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release()
                Log.w(TAG, "The microphone could not be opened")
                return@withContext null
            }
            val total = (LENGTH_MS * RATE / 1000).toInt()
            val pcm = FloatArray(total)
            var n = 0
            try {
                recorder.startRecording()
                while (n < total && !shouldStop()) {
                    val read = recorder.read(pcm, n, minOf(FRAME, total - n), AudioRecord.READ_BLOCKING)
                    if (read <= 0) break
                    onLevel(levelDb(pcm, n, read), (n + read) * 1000L / RATE)
                    n += read
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
            pcm.copyOf(n)
        }

    /**
     * The print of [speech] (speechOnly's output): embedded in windows of up to a minute and
     * averaged, under the engine lock as every model is. Null without the voice model or when it fails.
     */
    suspend fun embed(context: Context, speech: FloatArray): FloatArray? {
        val (segmentation, embedding) = VoiceModel.paths(context) ?: return null
        return withContext(Dispatchers.Default) {
            EngineLock.mutex.withLock {
                val ptr = VoiceNative.open(segmentation.path, embedding.path, TranscriptionEngine.preferredThreadCount())
                if (ptr == 0L) return@withLock null
                try {
                    VoiceNative.embed(ptr, speech.copyOf(minOf(speech.size, (VoiceStep.EMBED_MS * RATE / 1000).toInt())))
                        .takeIf { it.isNotEmpty() }
                } finally {
                    VoiceNative.close(ptr)
                }
            }
        }
    }
}
