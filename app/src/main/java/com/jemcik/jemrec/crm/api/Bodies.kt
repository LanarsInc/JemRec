package com.jemcik.jemrec.crm.api

import org.json.JSONObject

enum class Presentation(val wire: String) { ALLOWED("allowed"), RESTRICTED("restricted"), UNKNOWN("unknown"), PAYPHONE("payphone") }

enum class Outcome(val wire: String) {
    REACHED("reached"), NO_ANSWER("no_answer"), VOICEMAIL("voicemail"),
    WRONG_NUMBER("wrong_number"), CALLBACK("callback"), NOT_INTERESTED("not_interested");

    companion object {
        fun fromWire(s: String?): Outcome? = entries.firstOrNull { it.wire == s }
    }
}

enum class DealChoice(val wire: String) { AUTO("auto"), DEAL("deal"), NONE("none") }

/** A next call: Oslo date `YYYY-MM-DD`, optional `HH:MM`, optional subject (§6). */
data class NextCall(val dueDate: String, val dueTime: String? = null, val subject: String? = null) {
    fun json(): JSONObject = JSONObject().put("due_date", dueDate).also { o ->
        dueTime?.let { o.put("due_time", it) }
        subject?.takeIf { it.isNotBlank() }?.let { o.put("subject", it.take(MAX_SUBJECT)) }
    }

    companion object {
        const val MAX_SUBJECT = 300

        fun fromJson(o: JSONObject?): NextCall? = o?.let {
            NextCall(it.getString("due_date"), it.optStringOrNull("due_time"), it.optStringOrNull("subject"))
        }
    }
}

/** Everything step 1 says about a call (§6). */
data class CallFacts(
    val clientCallId: String,
    val outgoing: Boolean,
    val answered: Boolean,
    val phone: String?,
    val presentation: Presentation,
    val countryIso: String?,
    val startedAtMs: Long,
    val durationSeconds: Int,
    val outcome: Outcome,
    val choice: DealChoice,
    val dealId: Int?,
    val chosenByUser: Boolean?,
    val next: NextCall?,
    val transcriptExpected: Boolean,
)

/** The transcript and summary of step 2 (§8), already cut to size (ContentText). */
data class Content(
    val summaryText: String,
    val transcriptText: String,
    val language: String,
    val transcriptTruncated: Boolean,
)

/**
 * The bodies the app sends, as JSON text. Optional fields are left out rather
 * than sent as null: §1 makes the two mean the same, and leaving them out keeps
 * a body equal to the document's examples. Unknown fields are refused by the
 * server (§1), so nothing is added that the document does not name.
 */
object Bodies {

    fun exchange(idToken: String, deviceId: String, label: String, appVersion: String, sdk: Int): String =
        JSONObject()
            .put("id_token", idToken)
            .put(
                "device", JSONObject()
                    .put("id", deviceId)
                    .put("label", label.trim().ifEmpty { "Android" }.take(100))
                    .put("app_version", appVersion.take(32))
                    .put("android_sdk", sdk),
            ).toString()

    fun acknowledge(version: String): String = JSONObject().put("version", version).toString()

    fun lookup(phone: String, countryIso: String?): String =
        JSONObject().put("phone", phone.take(64)).also { o -> countryIso?.let { o.put("country_iso", it) } }.toString()

    /** §10: 2-100 characters after trimming; the caller checks the lower bound. */
    fun search(q: String): String = JSONObject().put("q", q.trim().take(MAX_SEARCH)).toString()

    const val MIN_SEARCH = 2
    const val MAX_SEARCH = 100

    fun call(f: CallFacts): String {
        // A number is sent only when the call log shows it (§6: not `allowed`
        // means `phone` is ignored, and the API says it must then be null).
        val phone = if (f.presentation == Presentation.ALLOWED) f.phone?.take(64) else null
        // A missed incoming call refuses `next` (§6), and only `deal` takes a deal id.
        val missedIncoming = !f.outgoing && !f.answered
        val o = JSONObject()
            .put("client_call_id", f.clientCallId)
            .put("direction", if (f.outgoing) "outgoing" else "incoming")
            .put("answered", f.answered)
            .put("phone", phone ?: JSONObject.NULL)
            .put("presentation", f.presentation.wire)
        f.countryIso?.let { o.put("country_iso", it) }
        o.put("started_at", Instants.format(f.startedAtMs))
            .put("duration_seconds", if (f.answered) f.durationSeconds.coerceIn(0, MAX_DURATION) else 0)
            .put("outcome", f.outcome.wire)
            .put("deal_choice", f.choice.wire)
        if (f.choice == DealChoice.DEAL) f.dealId?.let { o.put("deal_id", it) }
        f.chosenByUser?.let { o.put("chosen_by", if (it) "user" else "auto") }
        if (!missedIncoming) f.next?.let { o.put("next", it.json()) }
        o.put("transcript_expected", f.transcriptExpected)
        return o.toString()
    }

    /** Step 1 again, without `next`: §11's answer to a 400 that names it. */
    fun withoutNext(body: String): String = JSONObject(body).apply { remove("next") }.toString()

    fun pick(choice: DealChoice, dealId: Int?, next: NextCall?): String {
        val o = JSONObject().put("deal_choice", choice.wire)
        if (choice == DealChoice.DEAL) {
            o.put("deal_id", requireNotNull(dealId) { "a deal choice needs its deal" })
            next?.let { o.put("next", it.json()) }
        }
        return o.toString()
    }

    /** [revision] above 0 replaces texts the server has (LANARS, 2026-11-05); 0 is left out, as before. */
    fun ready(c: Content, revision: Int = 0): String = JSONObject()
        .put("content_status", "ready")
        .also { if (revision > 0) it.put("revision", revision) }
        .put("summary_text", c.summaryText)
        .put("transcript_text", c.transcriptText)
        .put("language", c.language)
        .put("summary_language", "en")
        .put("transcript_truncated", c.transcriptTruncated)
        .toString()

    fun failed(): String = JSONObject().put("content_status", "failed").toString()

    private const val MAX_DURATION = 86_400
}
