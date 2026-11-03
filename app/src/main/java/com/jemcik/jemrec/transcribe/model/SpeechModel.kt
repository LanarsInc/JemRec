package com.jemcik.jemrec.transcribe.model

/**
 * The whisper.cpp models JemRec offers.
 *
 * Two general ones from CallVault's catalogue (5cfa2c60), with CallVault's
 * measurements, and two of the National Library of Norway's NB-Whisper, which
 * is Whisper trained further on Norwegian speech (Apache-2.0, ungated).
 * Sizes and digests are the published Git-LFS values, read from the Hugging
 * Face API on 2026-10-29. They are load-bearing: ggml's answer to a corrupt
 * model is a crash in native code, so nothing is loaded before its digest
 * matches (ModelRepository).
 *
 * CallVault's turbo q5_0 is left out. It measured 1.98x slower than q8_0 for
 * the same accuracy (OnePlus 9 Pro, a 295 s Hebrew call, 2026-08-26): ggml's
 * ARM kernels accelerate Q8_0 and not Q5. No install of this fork has it, so
 * nothing is stranded by its absence.
 *
 * The NB models are published only as q5_0 (and f16), so they take that slow
 * path too. Whether their Norwegian is worth it is measured on the phone
 * (research.md R14); if so and too slow, a q8_0 made with whisper.cpp's
 * quantize can be tried through the debug import first.
 *
 * Ordered by download size, as Settings lists them.
 *
 * @param languages the languages it is for; null for all of them.
 * @param realTimeFactor seconds of work per second of audio - an opening
 *   guess, replaced by this phone's own figure after one run
 *   (TranscribeSettings.measuredRtf). CallVault's, on its phones.
 */
enum class SpeechModel(
    override val id: String,
    val label: String,
    override val fileName: String,
    override val url: String,
    override val sha256: String,
    override val sizeBytes: Long,
    val languages: Set<String>?,
    val realTimeFactor: Double,
) : DownloadableModel {

    /** NB-Whisper small: Norwegian only. No measurement yet; small's speed assumed. */
    NB_SMALL_Q5_0(
        id = "nb-small-q5_0",
        label = "NB-Whisper small",
        fileName = "nb-small-q5_0.bin",
        url = "https://huggingface.co/NbAiLab/nb-whisper-small/resolve/main/ggml-model-q5_0.bin",
        sha256 = "2a9025afb6e825fc4ae6a46671e0cb2f43e62f1dec87270deea6fe61b5285a20",
        sizeBytes = 175_209_680L,
        languages = setOf("no"),
        realTimeFactor = 1.0,
    ),

    /** Whisper small: fast, every language; CallVault found Hebrew comes out as gist. RTF 0.38 on a OnePlus 9 Pro. */
    SMALL_Q5_1(
        id = "small-q5_1",
        label = "Whisper small",
        fileName = "ggml-small-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        sizeBytes = 190_085_487L,
        languages = null,
        realTimeFactor = 0.99,
    ),

    /** NB-Whisper medium: Norwegian only, between small and turbo in size. Not measured yet. */
    NB_MEDIUM_Q5_0(
        id = "nb-medium-q5_0",
        label = "NB-Whisper medium",
        fileName = "nb-medium-q5_0.bin",
        url = "https://huggingface.co/NbAiLab/nb-whisper-medium/resolve/main/ggml-model-q5_0.bin",
        sha256 = "18733de634af639a43b0f8c5f5a2ea0920de4c5b32a5570ec130981581c0e5e7",
        sizeBytes = 539_212_484L,
        languages = setOf("no"),
        realTimeFactor = 2.0,
    ),

    /**
     * Whisper large-v3-turbo, 8-bit: CallVault's default and best. RTF 0.72
     * and 1258 MB at peak on a OnePlus 9 Pro (2026-08-26), never less accurate
     * than q5_0 and once clearly better (7.8% against 13.7% word error rate on
     * a known Hebrew paragraph).
     */
    TURBO_Q8_0(
        id = "turbo-q8_0",
        label = "Whisper large-v3-turbo",
        fileName = "ggml-large-v3-turbo-q8_0.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin",
        sha256 = "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1",
        sizeBytes = 874_188_075L,
        languages = null,
        realTimeFactor = 1.10,
    );

    /** Whether it is meant for [language] (an ISO code; null or "auto" means any). */
    fun isFor(language: String?): Boolean =
        languages == null || (language != null && language in languages)

    /** Made for one language rather than all: preferred for it when downloaded. */
    val isLanguageSpecific: Boolean get() = languages != null

    /**
     * Opening guess at loading it, before the phone has timed it once: the
     * file read off flash at a pessimistic 200 MB/s (CallVault's figure, from
     * its OnePlus phones). Replaced by TranscribeSettings.measuredLoadMs.
     */
    val seedLoadMs: Long get() = sizeBytes / SEED_LOAD_BYTES_PER_MS

    companion object {
        private const val SEED_LOAD_BYTES_PER_MS = 200_000L

        fun fromId(id: String?): SpeechModel? = entries.firstOrNull { it.id == id }

        /**
         * The general model to recommend, from how many fast cores the phone
         * has. An inference until measured on the A24 (research.md R1, R3):
         * turbo is about twice small's work, and a phone with two fast cores
         * (the A24's Helio G99) is expected at two to three times CallVault's
         * OnePlus 9 Pro times. T097 replaces this with measured thresholds.
         */
        fun recommendedFor(bigCores: Int): SpeechModel =
            if (bigCores >= 4) TURBO_Q8_0 else SMALL_Q5_1
    }
}
