package com.jemcik.jemrec.transcribe

import java.util.Locale

/**
 * What a transcription language is called on screen: in its own language,
 * as a language picker names them ("Norsk", "Українська"), so a person finds
 * theirs whatever the phone is set to; English beside it for the rest.
 */
object LanguageNames {

    /** The offered languages sorted by the name shown, as CallVault's picker sorts them. */
    val offered: List<String> = TranscriptionLanguageChoice.SUPPORTED.sortedBy { own(it).lowercase(Locale.ROOT) }

    /** "Norsk", or "Detect automatically" for [TranscriptionLanguageChoice.AUTO]. */
    fun own(code: String): String {
        if (code == TranscriptionLanguageChoice.AUTO) return "Detect automatically"
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** "Norwegian"; null for detect, which has no other name. */
    fun english(code: String): String? =
        if (code == TranscriptionLanguageChoice.AUTO) null else Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)

    /** "Norsk (Norwegian)", or the one name when the two are the same. */
    fun both(code: String): String {
        val english = english(code)
        val own = own(code)
        return if (english == null || english.equals(own, ignoreCase = true)) own else "$own ($english)"
    }
}
