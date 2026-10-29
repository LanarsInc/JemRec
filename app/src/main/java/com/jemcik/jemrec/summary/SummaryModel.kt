package com.jemcik.jemrec.summary

import com.jemcik.jemrec.transcribe.model.DownloadableModel

/**
 * The language models JemRec summarises with, offered by the phone's memory.
 *
 * CallVault's one is Gemma 4 E2B: chosen on 2026-08-21 because across four
 * controlled runs it was the only candidate that invented nothing - for a
 * record of a real call, a plausible fabrication is the worst output there
 * is. But it peaks at 3.5 GB (CallVault, OnePlus 12), and on a 5.8 GB phone
 * like the Galaxy A24 that leaves Android too little: the app would be killed
 * mid-summary. So a smaller one is offered beside it, and a model is only
 * offered where the phone has at least twice its peak ([fits]).
 *
 * Gemma 3 1B was tried as a third and is not offered: on the A24 (2026-10-29)
 * it invented a detail in English, wrote the Norwegian sample's summary in
 * English with a delivery window nobody gave and broke off mid-JSON, and gave
 * nothing for Ukrainian (research.md R14).
 *
 * Sizes and digests from the Hugging Face API, 2026-10-29. Licences: Apache-2.0.
 */
enum class SummaryModel(
    override val id: String,
    val label: String,
    override val fileName: String,
    override val url: String,
    override val sha256: String,
    override val sizeBytes: Long,
    val peakMemoryBytes: Long,
    /** Whether the peak was measured on a phone, or is still an estimate. */
    val peakMeasured: Boolean,
) : DownloadableModel {

    /**
     * Qwen3 1.7B: multilingual, its reasoning switched off by the prompt; the
     * one for 6 GB phones. Measured on the Galaxy A24, 2026-10-29: 2.5 GB
     * resident while summarising, 92-243 s for a two-minute call, load
     * included; English, Norwegian and Ukrainian samples in their own
     * languages with the gist right, but a detail wrong now and then (an
     * extra cost read as included, a time an hour off), and lines copied
     * where it should sum up, which SummaryTidy drops (research.md R14).
     */
    QWEN3_1_7B_Q4_K_M(
        id = "qwen3-1.7b-q4_k_m",
        label = "Qwen3 1.7B",
        fileName = "Qwen3-1.7B-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
        sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        sizeBytes = 1_107_409_472L,
        peakMemoryBytes = 2_600_000_000L,
        peakMeasured = true,
    ),

    /**
     * Gemma 4 E2B, quantisation-aware trained, Q4_K_XL: CallVault's model,
     * which invented nothing in its checks. Q4_K keeps ggml's repacked ARM
     * kernels; a "finer" Q5 would run slower (CallVault). Peak 3.5 GB measured
     * on a OnePlus 12, deliberately not lowered for the smaller QAT file:
     * llama repacks weights at load, so memory is not the file size.
     */
    GEMMA_4_E2B_QAT_Q4_K_XL(
        id = "gemma-4-e2b-it-qat-q4_k_xl",
        label = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        url = "https://huggingface.co/unsloth/gemma-4-E2B-it-qat-GGUF/resolve/main/gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        sha256 = "e531007218dfab990486a5de7676a6932d6ea8dea233d1f698d7c21cf8a16889",
        sizeBytes = 2_620_370_976L,
        peakMemoryBytes = 3_500_000_000L,
        peakMeasured = true,
    );

    /**
     * Whether a phone with [totalMemBytes] can hold it: twice the peak, so
     * the rest of the phone keeps as much again. A rule of thumb, not a
     * measurement; the run itself also checks free memory before it loads.
     */
    fun fits(totalMemBytes: Long): Boolean = totalMemBytes >= 2 * peakMemoryBytes

    /**
     * Free memory a run needs before it starts (ActivityManager's availMem):
     * the peak less the weights' file, plus a margin. The weights are read from
     * the model file, so they are page cache Android can drop and read again,
     * not memory taken; what is taken is llama's repacked copy and its working
     * space. And availMem leaves out the cached apps Android closes when memory
     * runs short. Measured on the Galaxy A24, 2026-10-29: the full peak (2.6 GB)
     * refused Qwen at 2.1 to 2.4 GB free, while the phone had 3.2 GB counting
     * cached apps, and the same model had run at 2.5 GB resident.
     */
    val minFreeBytes: Long get() = peakMemoryBytes - sizeBytes + START_MARGIN_BYTES

    /**
     * The phone it needs, as phones are sold, for "Needs a phone with 8 GB":
     * the smallest of the usual sizes that holds twice the peak. Gemma 4's
     * 7.0 GB is an 8 GB phone, which reports about 7.5 GB to Android.
     */
    val needsGb: Int get() = PHONE_SIZES_GB.firstOrNull { it * 1_000_000_000L >= 2 * peakMemoryBytes } ?: PHONE_SIZES_GB.last()

    companion object {
        private val PHONE_SIZES_GB = listOf(4, 6, 8, 12, 16, 24)

        /** Room for the rest of the phone while a summary starts. */
        private const val START_MARGIN_BYTES = 300_000_000L

        /** When nothing else is known: the one expected to fit most phones. */
        val DEFAULT = QWEN3_1_7B_Q4_K_M

        fun fromId(id: String?): SummaryModel? = entries.firstOrNull { it.id == id }

        /** The best that fits: the last in the list (they are ordered smallest first), or null if none. */
        fun bestFor(totalMemBytes: Long): SummaryModel? = entries.lastOrNull { it.fits(totalMemBytes) }
    }
}
