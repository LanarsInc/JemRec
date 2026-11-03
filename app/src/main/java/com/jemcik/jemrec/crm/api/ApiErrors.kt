package com.jemcik.jemrec.crm.api

/**
 * What a failed request makes the app do: §11's table, and §2.2's for the
 * sign-in exchange, as one decision (specs/002, contracts/api-client.md).
 * Kept apart from the HTTP so every row is a unit test.
 */
sealed interface ApiFailure {
    /** Network error, timeout, not JSON, a redirect, 500, a parallel write: try later. */
    data object Retry : ApiFailure

    /** 404 "unknown endpoint": the server is older than this app. Hold, try much later. */
    data object ServerTooOld : ApiFailure

    /** 429 on anything but a lookup: wait this long. */
    data class RetryAfter(val seconds: Long) : ApiFailure

    /** A lookup that failed for any reason, 429 included: never retried (§5). */
    data object LookupGivenUp : ApiFailure

    /** 401 from the exchange: Google's token failed verification. */
    data object SignInFailed : ApiFailure

    /** 401 elsewhere: the token is dead; sign in again, then retry (§2.3). */
    data object SessionDead : ApiFailure

    /** 403 `notice_required`: show the notice, then retry (§3). */
    data object NoticeRequired : ApiFailure

    /** 409 on the notice: the version read is no longer current (§3.2). */
    data object NoticeChanged : ApiFailure

    /** 403 from the exchange with a reason the seller can act on (§2.2). */
    data class Refused(val reason: Reason) : ApiFailure

    /** LANARS blocked this phone: forget the session and all queued call data (§2.2). */
    data object Blocked : ApiFailure

    /** 403 `forbidden` outside the exchange: stop until the next sign-in (§11). */
    data object Forbidden : ApiFailure

    /** 426: hold everything and ask for an update. */
    data class UpgradeRequired(val detail: String?) : ApiFailure

    /** 404 on a call: it does not exist or is another user's. */
    data object CallGone : ApiFailure

    /** 409 `duplicate`: fetch the call and adopt the server's state. */
    data object Adopt : ApiFailure

    /** 400 naming `next`: resend once without it (§11). */
    data object DropNext : ApiFailure

    /** 400 on a `started_at` in the future: the phone's clock; retry in an hour. */
    data object ClockAhead : ApiFailure

    /** Any other 400, or a 405: an app bug. `detail` names a field, never a value. */
    data class Bug(val detail: String?) : ApiFailure

    enum class Reason { NO_USER, WRONG_DOMAIN, INACTIVE, ACCOUNT_MISMATCH, OTHER }
}

enum class Endpoint { EXCHANGE, SIGN_OUT, NOTICE, ACKNOWLEDGE, ME, LOOKUP, CALL, PICK, CONTENT, GET_CALL, PRIVATE }

object ApiErrors {

    /**
     * @param status the HTTP status, or null when there was no answer at all
     * @param error the body's `error`, or null when the body was not the API's JSON
     */
    fun classify(
        endpoint: Endpoint,
        status: Int?,
        error: String?,
        detail: String?,
        retryAfterSeconds: Long?,
    ): ApiFailure {
        // No answer, or an answer that is not the API's: a captive portal, a
        // gateway's HTML 502, a cut body. §1: retry later, never sign in again.
        if (status == null || error == null) {
            return if (endpoint == Endpoint.LOOKUP) ApiFailure.LookupGivenUp else ApiFailure.Retry
        }
        if (endpoint == Endpoint.LOOKUP) return ApiFailure.LookupGivenUp
        return when (status) {
            400 -> when {
                detail != null && detail.contains("next") -> ApiFailure.DropNext
                detail != null && detail.contains("started_at") && detail.contains("future") -> ApiFailure.ClockAhead
                else -> ApiFailure.Bug(detail)
            }
            401 -> if (endpoint == Endpoint.EXCHANGE) ApiFailure.SignInFailed else ApiFailure.SessionDead
            403 -> when {
                error == "notice_required" -> ApiFailure.NoticeRequired
                endpoint == Endpoint.EXCHANGE -> when (detail) {
                    "device_blocked" -> ApiFailure.Blocked
                    "no_user" -> ApiFailure.Refused(ApiFailure.Reason.NO_USER)
                    "domain_not_allowed", "email_unverified" -> ApiFailure.Refused(ApiFailure.Reason.WRONG_DOMAIN)
                    "inactive" -> ApiFailure.Refused(ApiFailure.Reason.INACTIVE)
                    "account_mismatch" -> ApiFailure.Refused(ApiFailure.Reason.ACCOUNT_MISMATCH)
                    else -> ApiFailure.Refused(ApiFailure.Reason.OTHER)
                }
                else -> ApiFailure.Forbidden
            }
            404 -> if (detail == "unknown endpoint") ApiFailure.ServerTooOld
            else if (endpoint in CALL_PATHS) ApiFailure.CallGone else ApiFailure.ServerTooOld
            405 -> ApiFailure.Bug(error)
            409 -> when {
                error == "duplicate" -> ApiFailure.Adopt
                endpoint == Endpoint.ACKNOWLEDGE -> ApiFailure.NoticeChanged
                else -> ApiFailure.Retry // "this call is being written": in parallel, try again
            }
            426 -> ApiFailure.UpgradeRequired(detail)
            429 -> ApiFailure.RetryAfter(retryAfterSeconds?.coerceAtLeast(1) ?: DEFAULT_RETRY_AFTER_S)
            else -> ApiFailure.Retry
        }
    }

    /** The paths with a call id in them, where a 404 means that call. */
    private val CALL_PATHS = setOf(Endpoint.PICK, Endpoint.CONTENT, Endpoint.GET_CALL, Endpoint.PRIVATE)

    /** A 429 without the header it promises: the 10-minute window is the longest wait it could mean. */
    private const val DEFAULT_RETRY_AFTER_S = 600L
}
