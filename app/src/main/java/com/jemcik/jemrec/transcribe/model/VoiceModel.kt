package com.jemcik.jemrec.transcribe.model

import android.content.Context
import com.jemcik.jemrec.transcribe.OwnerPrint
import java.io.File

/**
 * The voice model: what tells the two voices of a one-mix call apart
 * (specs/003-voice-labels). Two files, one row in Settings - the user has no
 * use for either alone, so they are downloaded, shown and deleted together.
 *
 * Each file goes through the same download, sha256 check and resume as the
 * speech and summary models (ModelDownloadWorker takes any
 * [DownloadableModel]); this object only says which two, and when both are
 * there. Plain files on Hugging Face, the same bytes as sherpa-onnx's release
 * assets (sha256 checked 2026-11-03, research.md V2), so nothing is unpacked
 * on the phone.
 */
object VoiceModel {

    /** Stored with what the voice step wrote, so a later model is told apart. */
    const val ID = "voices-pyannote3-titanet-s"

    /** pyannote segmentation-3.0, int8: who speaks when. MIT, CNRS. */
    val SEGMENTATION = Part(
        id = "voices-segmentation-pyannote3-int8",
        fileName = "pyannote-segmentation-3.0-int8.onnx",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx",
        sha256 = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
        sizeBytes = 1_540_506L,
    )

    /**
     * NVIDIA TitaNet-S: a voice as 192 numbers. Apache-2.0 (the NeMo
     * Toolkit's licence, NGC catalog). R16 found it as accurate as four
     * larger ones on a real call and the fastest of them on the A24.
     */
    val EMBEDDING = Part(
        id = "voices-embedding-titanet-s",
        fileName = "nemo-titanet-small.onnx",
        url = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/nemo_en_titanet_small.onnx",
        sha256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e",
        sizeBytes = 40_257_283L,
    )

    val files: List<DownloadableModel> = listOf(SEGMENTATION, EMBEDDING)

    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    /**
     * Measured on the Galaxy A24, 2026-11-03 (VoiceBridgeTest, a 216 s call):
     * the models loaded took 139 MB and the run 220 MB more, about 360 MB in
     * all; rounded up for a 5-minute chunk's audio and the embedding windows.
     */
    const val PEAK_MEMORY_BYTES = 400_000_000L

    /** Twice the peak, the rule the summary models follow. */
    fun fits(totalMemBytes: Long): Boolean = totalMemBytes >= 2 * PEAK_MEMORY_BYTES

    /**
     * Free memory the step needs before it starts. Unlike a GGUF, an ONNX
     * model is read into the process, not mapped, so the whole peak counts.
     */
    const val MIN_FREE_BYTES = PEAK_MEMORY_BYTES + 200_000_000L

    fun isInstalled(context: Context): Boolean = files.all { ModelRepository.isInstalled(context, it) }

    /** Both files' paths, or null unless both are complete and verified. */
    fun paths(context: Context): Pair<File, File>? {
        val segmentation = ModelRepository.pathFor(context, SEGMENTATION) ?: return null
        val embedding = ModelRepository.pathFor(context, EMBEDDING) ?: return null
        return segmentation to embedding
    }

    fun download(context: Context, wifiOnly: Boolean = true) {
        files.filterNot { ModelRepository.isInstalled(context, it) }
            .forEach { ModelDownloadWorker.enqueue(context, it, wifiOnly) }
    }

    fun cancel(context: Context) = files.forEach { ModelDownloadWorker.cancel(context, it) }

    /** The files, and the owner's voice print with them: its numbers mean nothing without this model. */
    fun delete(context: Context) {
        cancel(context)
        files.forEach { ModelRepository.delete(context, it) }
        OwnerPrint.store(context).delete()
    }

    /** One of the two files. */
    data class Part(
        override val id: String,
        override val fileName: String,
        override val url: String,
        override val sha256: String,
        override val sizeBytes: Long,
    ) : DownloadableModel
}
