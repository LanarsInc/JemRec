package com.jemcik.jemrec.crm.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Sales Engine answers, as data. The shapes are the API document's
 * (specs/002-sales-engine-call-logging/reference, cited as §n), parsed with
 * `org.json` like the app's summaries are: a dozen known shapes do not justify
 * a serialization plugin (research R1).
 *
 * Every parser here follows §1 "New fields": unknown fields are ignored for
 * free, and the three enums that may gain values fall back the way the
 * document says - an unknown call status is final, an unknown pending reason
 * is "several open deals", an unknown match is "none".
 */

data class UserInfo(val id: Int, val name: String, val email: String)

/** The answer to the sign-in exchange (§2.2). */
data class SessionData(
    val token: String,
    val tokenPrefix: String,
    val expiresAtMs: Long,
    val idleTimeoutDays: Int,
    val user: UserInfo,
    val noticeVersion: String,
    val noticeAcknowledged: Boolean,
    val minAppVersion: String?,
)

/** LANARS's notice (§3.1): shown as given, never reworded. */
data class Notice(
    val version: String,
    val acknowledged: Boolean,
    val title: String,
    val intro: String?,
    val sections: List<Section>,
) {
    data class Section(
        val heading: String,
        val paragraphs: List<String>,
        val items: List<String>,
        val ordered: List<String>,
    )
}

enum class Match { DEAL, INTERNAL, NONE }

enum class DealStatus { OPEN, WON, LOST }

/** A deal a call may go to; the same shape in every answer (§5). */
data class Candidate(
    val dealId: Int,
    val title: String,
    val orgName: String?,
    val status: DealStatus,
    val stageName: String,
    val ownerId: Int,
    val ownerName: String,
    val hasDueCall: Boolean,
)

/** The lookup's answer (§5). It carries no names, numbers or dates. */
data class Lookup(
    val match: Match,
    val candidates: List<Candidate>,
    val candidatesTotal: Int,
    val autoDealId: Int?,
    val suggestedDealId: Int?,
)

enum class CallStatus(val wire: String) {
    LOGGED("logged"), PENDING("pending"), DECLINED("declined"), NOT_IN_CRM("not_in_crm"),
    HIDDEN("hidden"), PRIVATE("private"), DELETED("deleted"), INTERNAL("internal"),

    /** A status this app does not know yet: final, as §1 says. */
    OTHER("");

    val isFinal: Boolean get() = this != PENDING
}

/**
 * Why a call waits for a pick (§6.2). NO_MATCH (API of 2026-11-03): a seller
 * with a weekly call goal called a number with no deal; its candidates are
 * suggestions, and any open deal may be picked (§7).
 */
enum class PendingReason { SEVERAL_OPEN_DEALS, ONLY_CLOSED_DEALS, DEAL_NOT_CANDIDATE, NO_MATCH }

enum class ContentStatus { EXPECTED, NONE, READY, FAILED }

/** The call object every call endpoint answers with (§6.2). */
data class CallState(
    /** Null only for a colleague's call, which the server does not keep. */
    val id: Int?,
    val clientCallId: String,
    val status: CallStatus,
    val dealId: Int?,
    val dealTitle: String?,
    val activityId: Int?,
    val completedPlannedCall: Boolean,
    val nextActivityId: Int?,
    val callbackActivityId: Int?,
    val contentStatus: ContentStatus,
    val pendingReason: PendingReason?,
    val candidates: List<Candidate>,
    val candidatesTotal: Int,
)

object ApiModels {

    fun session(data: JSONObject): SessionData {
        val notice = data.getJSONObject("notice")
        return SessionData(
            token = data.getString("token"),
            tokenPrefix = data.getString("token_prefix"),
            expiresAtMs = Instants.parse(data.getString("expires_at")),
            idleTimeoutDays = data.getInt("idle_timeout_days"),
            user = user(data.getJSONObject("user")),
            noticeVersion = notice.getString("version"),
            noticeAcknowledged = notice.getBoolean("acknowledged"),
            minAppVersion = data.optStringOrNull("min_app_version"),
        )
    }

    fun user(o: JSONObject) = UserInfo(o.getInt("id"), o.getString("name"), o.getString("email"))

    fun notice(data: JSONObject) = Notice(
        version = data.getString("version"),
        acknowledged = data.optBoolean("acknowledged", false),
        title = data.getString("title"),
        intro = data.optStringOrNull("intro"),
        sections = data.optJSONArray("sections").objects().map { s ->
            Notice.Section(
                heading = s.getString("heading"),
                paragraphs = s.optJSONArray("paragraphs").strings(),
                items = s.optJSONArray("items").strings(),
                ordered = s.optJSONArray("ordered").strings(),
            )
        },
    )

    fun lookup(data: JSONObject) = Lookup(
        match = when (data.optString("match")) {
            "deal" -> Match.DEAL
            "internal" -> Match.INTERNAL
            else -> Match.NONE
        },
        candidates = data.optJSONArray("candidates").objects().map(::candidate),
        candidatesTotal = data.optInt("candidates_total", 0),
        autoDealId = data.optIntOrNull("auto_deal_id"),
        suggestedDealId = data.optIntOrNull("suggested_deal_id"),
    )

    fun candidate(o: JSONObject): Candidate {
        val owner = o.getJSONObject("owner")
        return Candidate(
            dealId = o.getInt("deal_id"),
            title = o.getString("title"),
            orgName = o.optStringOrNull("org_name"),
            status = when (o.optString("status")) {
                "won" -> DealStatus.WON
                "lost" -> DealStatus.LOST
                else -> DealStatus.OPEN
            },
            stageName = o.optString("stage_name"),
            ownerId = owner.getInt("id"),
            ownerName = owner.getString("name"),
            hasDueCall = o.optBoolean("has_due_call", false),
        )
    }

    /** The call object, from an answer's `data` (which wraps it in `call`). */
    fun call(data: JSONObject): CallState {
        val c = data.getJSONObject("call")
        val deal = c.optJSONObject("deal")
        return CallState(
            id = c.optIntOrNull("id"),
            clientCallId = c.getString("client_call_id"),
            status = c.optString("status").let { s -> CallStatus.entries.firstOrNull { it.wire == s && it != CallStatus.OTHER } }
                ?: CallStatus.OTHER,
            dealId = deal?.optIntOrNull("id"),
            dealTitle = deal?.optStringOrNull("title"),
            activityId = c.optIntOrNull("activity_id"),
            completedPlannedCall = c.optBoolean("completed_planned_call", false),
            nextActivityId = c.optIntOrNull("next_activity_id"),
            callbackActivityId = c.optIntOrNull("callback_activity_id"),
            contentStatus = when (c.optString("content_status")) {
                "expected" -> ContentStatus.EXPECTED
                "ready" -> ContentStatus.READY
                "failed" -> ContentStatus.FAILED
                else -> ContentStatus.NONE
            },
            pendingReason = if (c.isNull("pending_reason")) null else when (c.optString("pending_reason")) {
                "only_closed_deals" -> PendingReason.ONLY_CLOSED_DEALS
                "deal_not_candidate" -> PendingReason.DEAL_NOT_CANDIDATE
                "no_match" -> PendingReason.NO_MATCH
                else -> PendingReason.SEVERAL_OPEN_DEALS
            },
            candidates = c.optJSONArray("candidates").objects().map(::candidate),
            candidatesTotal = c.optInt("candidates_total", 0),
        )
    }

    /** `POST /calls/search`'s answer (§10): open deals, best match first. */
    fun deals(data: JSONObject): List<Candidate> = data.optJSONArray("deals").objects().map(::candidate)

    /** Back to JSON, for keeping the last answer on the work call. */
    fun callJson(s: CallState): JSONObject = JSONObject().put(
        "call", JSONObject()
            .put("id", s.id ?: JSONObject.NULL)
            .put("client_call_id", s.clientCallId)
            .put("status", if (s.status == CallStatus.OTHER) "unknown" else s.status.wire)
            .put("deal", if (s.dealId == null) JSONObject.NULL else JSONObject().put("id", s.dealId).put("title", s.dealTitle))
            .put("activity_id", s.activityId ?: JSONObject.NULL)
            .put("completed_planned_call", s.completedPlannedCall)
            .put("next_activity_id", s.nextActivityId ?: JSONObject.NULL)
            .put("callback_activity_id", s.callbackActivityId ?: JSONObject.NULL)
            .put("content_status", s.contentStatus.name.lowercase())
            .put("pending_reason", s.pendingReason?.name?.lowercase() ?: JSONObject.NULL)
            .put("candidates", JSONArray(s.candidates.map(::candidateJson)))
            .put("candidates_total", s.candidatesTotal),
    )

    fun lookupJson(l: Lookup): JSONObject = JSONObject()
        .put("match", l.match.name.lowercase())
        .put("candidates", JSONArray(l.candidates.map(::candidateJson)))
        .put("candidates_total", l.candidatesTotal)
        .put("auto_deal_id", l.autoDealId ?: JSONObject.NULL)
        .put("suggested_deal_id", l.suggestedDealId ?: JSONObject.NULL)

    fun candidateJson(c: Candidate): JSONObject = JSONObject()
        .put("deal_id", c.dealId)
        .put("title", c.title)
        .put("org_name", c.orgName ?: JSONObject.NULL)
        .put("status", c.status.name.lowercase())
        .put("stage_name", c.stageName)
        .put("owner", JSONObject().put("id", c.ownerId).put("name", c.ownerName))
        .put("has_due_call", c.hasDueCall)
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { if (isNull(it)) null else optString(it) }
