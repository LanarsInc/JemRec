package com.jemcik.jemrec.transcribe

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Whether a recording's two channels are the two sides of the call, so each
 * can be transcribed alone and its lines named You and Them - or one mix
 * copied twice, so they are transcribed as one and named nothing.
 *
 * JemRec puts you on the left and the other person on the right for
 * messenger calls and mixer-routed phone calls, always; for an ordinary phone
 * call the phone's driver decides. Measured 2026-10-29 on a Galaxy A24
 * (research.md R4): a Telegram call had a 20 ms level correlation of -0.46
 * with one side 10 dB louder in 92% of its frames; a phone call on the same
 * phone 1.000 and 0%. The two cases are far apart, so the line between them
 * is not a fine one: [MAX_CORRELATION] and [MIN_ONE_SIDED].
 *
 * Plain arithmetic over levels so it is tested off the phone; [measure]
 * gets the levels from the file.
 */
object ChannelSides {

    /** At or above this level correlation the channels carry the same sound. */
    const val MAX_CORRELATION = 0.9

    /** At least this share of frames with sound must have one side leading by [LEAD_DB]. */
    const val MIN_ONE_SIDED = 1.0 / 3

    const val LEAD_DB = 10.0

    /** A frame quieter than this on both sides is silence, and says nothing. */
    const val SOUND_DB = -45.0

    /** How much of the recording is looked at: enough speech to tell, without decoding a long call. */
    const val LOOK_MS = 60_000L

    const val FRAME_MS = 20

    /**
     * The decision for one recording, from [left] and [right] levels in dBFS
     * per 20 ms frame. A [setting] other than AUTO is the user's, and wins.
     * Too little sound to tell is MIX: naming lines wrongly is worse than not
     * naming them.
     */
    fun decide(left: DoubleArray, right: DoubleArray, setting: Sides = Sides.AUTO): Sides {
        if (setting != Sides.AUTO) return setting
        val n = minOf(left.size, right.size)
        val sound = (0 until n).filter { maxOf(left[it], right[it]) > SOUND_DB }
        if (sound.size < MIN_SOUND_FRAMES) return Sides.MIX
        val l = DoubleArray(sound.size) { left[sound[it]] }
        val r = DoubleArray(sound.size) { right[sound[it]] }
        val oneSided = sound.indices.count { kotlin.math.abs(l[it] - r[it]) > LEAD_DB }.toDouble() / sound.size
        return if (correlation(l, r) < MAX_CORRELATION && oneSided >= MIN_ONE_SIDED) Sides.SPLIT else Sides.MIX
    }

    /** One second of sound in all: below it, nothing can be told. */
    private const val MIN_SOUND_FRAMES = 1000 / FRAME_MS

    /** Pearson correlation; two flat lines (identical silence, a constant tone) count as equal. */
    internal fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        if (n == 0) return 1.0
        val ma = a.average()
        val mb = b.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db
            va += da * da
            vb += db * db
        }
        if (va == 0.0 || vb == 0.0) return if (va == vb) 1.0 else 0.0
        return cov / sqrt(va * vb)
    }

    /**
     * Levels in dBFS per [FRAME_MS] for each channel of interleaved [pcm],
     * [channels] wide. A mono file gives the same levels twice, which
     * [decide] reads as MIX.
     */
    fun levels(pcm: ShortArray, length: Int, channels: Int, sampleRate: Int): Pair<DoubleArray, DoubleArray> {
        val frame = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val frames = length / channels / frame
        val left = DoubleArray(frames)
        val right = DoubleArray(frames)
        val r = if (channels > 1) 1 else 0
        for (f in 0 until frames) {
            var sl = 0.0
            var sr = 0.0
            for (i in 0 until frame) {
                val base = (f * frame + i) * channels
                val a = pcm[base] / 32768.0
                val b = pcm[base + r] / 32768.0
                sl += a * a
                sr += b * b
            }
            left[f] = 20 * log10(sqrt(sl / frame) + 1e-9)
            right[f] = 20 * log10(sqrt(sr / frame) + 1e-9)
        }
        return left to right
    }
}
