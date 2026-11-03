package com.jemcik.jemrec.transcribe

import android.content.Context
import com.jemcik.jemrec.transcribe.model.ModelRepository
import com.jemcik.jemrec.transcribe.model.SpeechModel
import java.io.File

/** A speech model ready to load: its id (stored with the transcript) and its file. */
data class ReadyModel(val id: String, val label: String, val file: File)

/**
 * Which speech model a transcription uses, and the language it is given.
 *
 * The model: one made for the call's language (NB-Whisper for Norwegian)
 * when it is downloaded and the user has not switched that off; otherwise the
 * one chosen in Settings; otherwise the one recommended for this phone. An
 * imported model (debug build) is used only when chosen by its id.
 */
object ModelChoice {

    /**
     * The general model to use: [chosenId] when it names one, else
     * [recommended]. Pure, for the tests; [speechModelFor] reads the phone.
     */
    fun pick(
        language: String?,
        chosenId: String?,
        languageModels: Boolean,
        installed: Set<SpeechModel>,
        recommended: SpeechModel,
    ): SpeechModel {
        if (languageModels && language != null) {
            // The larger first: the medium NB model over the small one when both are there.
            installed.filter { it.isLanguageSpecific && it.isFor(language) }.maxByOrNull { it.sizeBytes }?.let { return it }
        }
        val chosen = SpeechModel.fromId(chosenId)?.takeUnless { it.isLanguageSpecific }
        return chosen ?: recommended
    }

    /** The phone's recommendation, from how many fast cores it has (SpeechModel.recommendedFor). */
    fun recommended(): SpeechModel = SpeechModel.recommendedFor(bigCores())

    /** The model for [language], with its file if it is on the phone; null file means it is still to download. */
    fun speechModelFor(context: Context, language: String?): Pair<SpeechModel?, ReadyModel?> {
        val settings = TranscribeSettings.of(context)
        ModelRepository.importedFile(context, settings.speechModelId.orEmpty())?.let { file ->
            return null to ReadyModel(ModelRepository.importedId(file), file.name, file)
        }
        val installed = ModelRepository.installedModels(context).toSet()
        val model = pick(language, settings.speechModelId, settings.languageModels, installed, recommended())
        val file = ModelRepository.pathFor(context, model)
        return model to file?.let { ReadyModel(model.id, model.label, it) }
    }

    /**
     * The language pinned for [perRecording] or by the Settings choice: an ISO
     * code, or null to detect it (LanguageGuess) - which is also what an unset
     * Settings language means.
     */
    fun language(perRecording: String?, setting: String?): String? {
        // Settings keeps "detect" as "auto"; resolve() wants it as null, as CallVault's preferences hand it over.
        val pinned = setting.takeUnless { it == TranscriptionLanguageChoice.AUTO }
        return TranscriptionLanguageChoice.resolve(perRecording, pinned)
    }

    /**
     * The cores clocked above the slowest cluster: the A24's two Cortex-A76
     * against its six A55s (2.2 against 2.0 GHz, read 2026-10-29). Every core
     * when they are all alike, or when the clocks cannot be read.
     */
    fun bigCores(): Int {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        if (freqs.isEmpty()) return Runtime.getRuntime().availableProcessors()
        val slowest = freqs.min()
        return freqs.count { it > slowest }.takeIf { it > 0 } ?: freqs.size
    }
}
