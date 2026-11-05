package com.jemcik.jemrec.transcribe

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A one-mix call's audio as whisper decoded it, kept in the app's cache for
 * the voice step that follows, so the call is decoded once, not twice
 * (specs/003-voice-labels, SC-003).
 *
 * Measured on the Galaxy A24, 2026-11-03 (debug build): the voice step on a
 * 252 s call took 147 s, 57 s of them decoding the Ogg file again - 0.58 of
 * the call's length, over the 0.5 SC-003 allows; telling the voices apart
 * itself was 0.35. Holding the decoded call in memory instead would undo the
 * chunking that keeps memory flat (230 MB of samples for an hour), so each
 * chunk goes to a file as it is decoded: 16 kHz mono 16-bit, 32 KB a second,
 * 8 MB for that call. Written only while the voice model is there.
 *
 * The app's own cache, never shared or backed up, and cleared when the
 * transcription ends however it ends (TranscriptionWorker); the recording
 * itself is on the phone anyway. One call at a time, as transcriptions are
 * (EngineLock).
 */
class MixCache(private val dir: File) {

    /** One chunk: where its audio really starts, where its own part begins, and its file. */
    data class Piece(val startMs: Long, val keepFromMs: Long, val file: File)

    /** Starts a call's cache, dropping whatever another call left. */
    fun start(uri: Uri) = start(uri.toString())

    fun start(key: String) {
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, KEY).writeText(key)
    }

    /** Chunk [index] of the call: [audio] (16 kHz mono float) starting at [startMs], its own from [keepFromMs]. */
    fun put(index: Int, startMs: Long, keepFromMs: Long, audio: FloatArray) {
        if (!File(dir, KEY).isFile) return
        val bytes = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in audio) bytes.putShort((x.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        val tmp = File(dir, "$index.tmp")
        tmp.writeBytes(bytes.array())
        tmp.renameTo(File(dir, "${index}_${startMs}_$keepFromMs$PCM"))
    }

    /** The call's chunks in order, or null when the cache holds another call or none. */
    fun pieces(uri: Uri): List<Piece>? = pieces(uri.toString())

    fun pieces(key: String): List<Piece>? {
        if (runCatching { File(dir, KEY).readText() }.getOrNull() != key) return null
        val pieces = dir.listFiles { f -> f.name.endsWith(PCM) }.orEmpty().mapNotNull { f ->
            val parts = f.name.removeSuffix(PCM).split('_')
            if (parts.size != 3) return@mapNotNull null
            val index = parts[0].toIntOrNull() ?: return@mapNotNull null
            index to Piece(parts[1].toLongOrNull() ?: return@mapNotNull null, parts[2].toLongOrNull() ?: return@mapNotNull null, f)
        }.sortedBy { it.first }.map { it.second }
        return pieces.ifEmpty { null }
    }

    /** A piece's audio, 16 kHz mono float. */
    fun read(piece: Piece): FloatArray {
        val bytes = ByteBuffer.wrap(piece.file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.remaining() / 2) { bytes.short / Short.MAX_VALUE.toFloat() }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        private const val KEY = "call"
        private const val PCM = ".pcm"

        fun of(context: Context) = MixCache(File(context.applicationContext.cacheDir, "voice-mix"))
    }
}
