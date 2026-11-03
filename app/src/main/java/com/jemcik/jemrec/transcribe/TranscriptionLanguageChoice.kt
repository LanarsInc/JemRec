/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.jemcik.jemrec.transcribe

/**
 * Which language one transcription runs in: the language picked for that recording if there was one,
 * and the Settings language otherwise.
 *
 * **Why a choice exists at all.** Whisper left to detect is measurably worse: it writes Hebrew in Latin
 * letters and merges a whole call into one segment (CallVault). In this fork, unset means "detect", but
 * detected before the run and then pinned (LanguageGuess), so whisper itself is told a language; the
 * user can still pin one in Settings, or for one recording. That replaced CallVault's pin from the
 * phone's language (and this fork's fallback to Norwegian) on 2026-10-30, at the user's request: a
 * phone takes calls in more than one language, and a pin is one answer for all of them. The
 * per-recording answer is one-shot - picking English for one call does not repin the phone.
 *
 * **Why the choice is encoded rather than passed as a nullable string.** It travels through WorkManager
 * input data, where "the user chose auto-detect" and "the user chose nothing" would both arrive as null
 * and the setting would silently win over an explicit choice. [AUTO] is that missing value. Stored
 * settings have the same problem and the same answer — see `AppPreferences.getTranscriptionLanguage`.
 */
object TranscriptionLanguageChoice {

    /** Stands in for auto-detect, which has no language code of its own. Never sent to whisper. */
    const val AUTO = "auto"

    /**
     * The language codes the app offers, in no particular order — the picker sorts them by translated
     * name. Whisper knows far more; these are the ones with a translated label to show for them.
     */
    val SUPPORTED: List<String> =
        listOf("he", "en", "ar", "zh", "fr", "de", "hu", "it", "pl", "pt", "ru", "es", "vi", "el", "fa", "uk", "no")

    /** [language] (null meaning auto-detect) as a value that survives a trip through work input data. */
    fun encode(language: String?): String = language ?: AUTO

    /**
     * The language to transcribe in: null for auto-detect.
     *
     * @param chosen what [encode] produced for this recording, or null when nothing was picked.
     * @param setting the pinned Settings language, itself null for auto-detect.
     *
     * An unrecognised [chosen] falls back to [setting] rather than reaching whisper: input data outlives
     * an install, so a code retired in a later version must not become a language nobody speaks.
     */
    fun resolve(chosen: String?, setting: String?): String? = when {
        chosen == null -> setting
        chosen == AUTO -> null
        chosen in SUPPORTED -> chosen
        else -> setting
    }
}
