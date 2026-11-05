package com.jemcik.jemrec.transcribe

import android.content.Context
import com.jemcik.jemrec.transcribe.model.VoiceModel
import java.io.File

/**
 * The print of the phone owner's voice: what tells their voice from the other
 * person's in a one-mix call (specs/003-voice-labels, research.md V7-V8).
 *
 * A voice described as 192 numbers by the voice model - no sound is kept, and
 * nothing can be played back from it. Still, a voice print is biometric data
 * under GDPR, so it lives in one small file under noBackupFilesDir, which no
 * backup takes (the app allows none either), and goes with one tap: Delete,
 * or deleting the voice model. It never leaves the phone.
 *
 * From a sample the owner recorded, or else from the left side of their own
 * messenger calls (OwnerPrintFromCalls), the newest five; a sample always
 * replaces prints from calls, and calls never replace a sample. A print made
 * by another voice model is void: its numbers mean nothing to the new one.
 */
enum class PrintSource { SAMPLE, CALLS }

/** A print as kept: where it came from, when, and from how many calls. */
class Print(val source: PrintSource, val madeAt: Long, val calls: List<FloatArray>, val vector: FloatArray) {
    val callCount: Int get() = calls.size
}

/**
 * The file's rules, apart from Android so they are tested on the JVM: a plain
 * text file of `key=value` lines, the vectors as comma-separated floats.
 */
class OwnerPrintStore(private val file: File, private val modelId: String) {

    /** The print, or null when there is none, it is unreadable, or another model made it. */
    fun read(): Print? = runCatching {
        if (!file.isFile) return null
        val lines = file.readLines().filter { '=' in it }
        val values = lines.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (values["version"] != VERSION || values["model"] != modelId) return null
        Print(
            source = PrintSource.valueOf(values.getValue("source")),
            madeAt = values.getValue("madeAt").toLong(),
            calls = lines.filter { it.startsWith("call=") }.map { floats(it.substringAfter('=')) },
            vector = floats(values.getValue("vector")),
        )
    }.getOrNull()

    /** A recorded sample's embedding: the print from now on, whatever was there. */
    fun saveSample(vector: FloatArray, now: Long) = write(Print(PrintSource.SAMPLE, now, emptyList(), vector))

    /**
     * A messenger call's left side, offered by OwnerPrintFromCalls. Kept with the newest calls
     * when the print comes from calls or there is none; ignored when the owner recorded a sample.
     * Returns the print now kept.
     */
    fun addCall(offered: FloatArray, now: Long): Print? {
        val current = read()
        if (current?.source == PrintSource.SAMPLE) return current
        val kept = OwnerPrintFromCalls.keep(current?.calls.orEmpty(), offered)
        val print = Print(PrintSource.CALLS, now, kept, OwnerPrintFromCalls.print(kept) ?: return current)
        write(print)
        return print
    }

    fun delete() {
        file.delete()
    }

    private fun write(print: Print) {
        file.parentFile?.mkdirs()
        val text = buildString {
            appendLine("version=$VERSION")
            appendLine("model=$modelId")
            appendLine("source=${print.source.name}")
            appendLine("madeAt=${print.madeAt}")
            appendLine("vector=${print.vector.joinToString(",")}")
            print.calls.forEach { appendLine("call=${it.joinToString(",")}") }
        }
        // Whole or not at all: a print half written would be read as another voice.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not keep the voice print")
        }
    }

    private fun floats(text: String) = text.split(',').map { it.toFloat() }.toFloatArray()

    private companion object { const val VERSION = "1" }
}

/** The app's one print: in noBackupFilesDir/voice/, for the voice model there is. */
object OwnerPrint {
    fun store(context: Context): OwnerPrintStore =
        OwnerPrintStore(File(context.applicationContext.noBackupFilesDir, "voice/print.txt"), VoiceModel.ID)
}
