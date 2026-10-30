package com.jemcik.jemrec.transcribe

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/** Who spoke: from the channels when they differ, always from them, or never. */
enum class Sides(val key: String) {
    AUTO("auto"), SPLIT("split"), MIX("mix");

    companion object {
        fun of(key: String?): Sides = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * When recordings are transcribed without being asked one by one. After each
 * call by default: the user's choice, 2026-10-30. It costs nothing until a
 * speech model is downloaded, which only a tap does (TranscriptionScheduler).
 */
enum class TranscribeMode(val key: String) {
    MANUAL("manual"), AFTER_CALL("after_call"), OVERNIGHT("overnight");

    companion object {
        fun of(key: String?): TranscribeMode = entries.firstOrNull { it.key == key } ?: AFTER_CALL
    }
}

/**
 * The settings of transcripts and summaries, in the app's one preferences
 * file (Prefs). Keys and defaults: specs/001-transcripts-summaries/data-model.md.
 *
 * Plain reads and writes over [prefs], so a test can hand it any
 * SharedPreferences. Model ids are kept as strings: an id a later version no
 * longer offers must read as "not chosen", not crash, and the catalogues
 * decide that (SpeechModel.fromId, SummaryModel.fromId).
 *
 * Language: null means the user has not chosen, and TranscriptionLanguage
 * gives the default (the phone's own if offered, else Norwegian); "auto" is a
 * choice of its own, detect automatically. The two must not collapse into one
 * null - CallVault's TranscriptionLanguageChoice explains why.
 */
class TranscribeSettings(private val prefs: SharedPreferences) {

    var language: String?
        get() = prefs.getString(LANGUAGE, null)
        set(value) = prefs.edit { putString(LANGUAGE, value) }

    var speechModelId: String?
        get() = prefs.getString(SPEECH_MODEL, null)
        set(value) = prefs.edit { putString(SPEECH_MODEL, value) }

    /** Use a model made for the language (NB-Whisper for Norwegian) when it is downloaded. */
    var languageModels: Boolean
        get() = prefs.getBoolean(LANGUAGE_MODELS, true)
        set(value) = prefs.edit { putBoolean(LANGUAGE_MODELS, value) }

    var sides: Sides
        get() = Sides.of(prefs.getString(SIDES, null))
        set(value) = prefs.edit { putString(SIDES, value.key) }

    var mode: TranscribeMode
        get() = TranscribeMode.of(prefs.getString(MODE, null))
        set(value) = prefs.edit { putString(MODE, value.key) }

    /** Overnight only while charging. A backlog is hours of full CPU. */
    var chargingOnly: Boolean
        get() = prefs.getBoolean(CHARGING_ONLY, true)
        set(value) = prefs.edit { putBoolean(CHARGING_ONLY, value) }

    /** Threads for whisper and llama; 0 means the measured default for this phone. */
    var threads: Int
        get() = prefs.getInt(THREADS, 0).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(THREADS, value.coerceAtLeast(0)) }

    var summaryModelId: String?
        get() = prefs.getString(SUMMARY_MODEL, null)
        set(value) = prefs.edit { putString(SUMMARY_MODEL, value) }

    /** On by default, with the mode; nothing happens until a summary model is downloaded (SummaryAfterTranscript). */
    var summariseAfterTranscript: Boolean
        get() = prefs.getBoolean(SUMMARY_AUTO, true)
        set(value) = prefs.edit { putBoolean(SUMMARY_AUTO, value) }

    /** Downloads are hundreds of megabytes; on mobile data only when asked. */
    var modelsWifiOnly: Boolean
        get() = prefs.getBoolean(WIFI_ONLY, true)
        set(value) = prefs.edit { putBoolean(WIFI_ONLY, value) }

    /**
     * Seconds of work per second of audio for [modelId], as this phone last
     * measured it; null until it has run once. Replaces the catalogue's
     * opening guess, which was measured on someone else's phone.
     */
    fun measuredRtf(modelId: String): Double? =
        prefs.getFloat(RTF + modelId, -1f).takeIf { it > 0f }?.toDouble()

    fun recordRtf(modelId: String, rtf: Double) {
        if (rtf > 0 && rtf.isFinite()) prefs.edit { putFloat(RTF + modelId, rtf.toFloat()) }
    }

    /** How long loading [modelId] took on this phone, in ms; null until measured. */
    fun measuredLoadMs(modelId: String): Long? =
        prefs.getLong(LOAD_MS + modelId, -1L).takeIf { it >= 0L }

    fun recordLoadMs(modelId: String, ms: Long) {
        if (ms >= 0) prefs.edit { putLong(LOAD_MS + modelId, ms) }
    }

    companion object {
        fun of(context: Context) = TranscribeSettings(Prefs.of(context))

        const val LANGUAGE = "tx_language"
        const val SPEECH_MODEL = "tx_model"
        const val LANGUAGE_MODELS = "tx_language_models"
        const val SIDES = "tx_sides"
        const val MODE = "tx_mode"
        const val CHARGING_ONLY = "tx_charging_only"
        const val THREADS = "tx_threads"
        const val SUMMARY_MODEL = "sum_model"
        const val SUMMARY_AUTO = "sum_auto"
        const val WIFI_ONLY = "models_wifi_only"
        private const val RTF = "tx_rtf."
        private const val LOAD_MS = "tx_load_ms."
    }
}
