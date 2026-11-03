package com.jemcik.jemrec.crm.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each row of §11's table, and §2.2's for the exchange (contracts/api-client.md). */
class ApiErrorsTest {

    private fun c(endpoint: Endpoint, status: Int?, error: String?, detail: String? = null, retryAfter: Long? = null) =
        ApiErrors.classify(endpoint, status, error, detail, retryAfter)

    @Test
    fun `no answer, or not the API's, is a network error - never a sign-in`() {
        assertEquals(ApiFailure.Retry, c(Endpoint.CALL, null, null))
        assertEquals(ApiFailure.Retry, c(Endpoint.CALL, 502, null))
        assertEquals(ApiFailure.Retry, c(Endpoint.CALL, 401, null)) // an HTML 401 from a portal
        assertEquals(ApiFailure.Retry, c(Endpoint.EXCHANGE, 302, null))
        assertEquals(ApiFailure.Retry, c(Endpoint.CALL, 500, "internal"))
    }

    @Test
    fun `a lookup is never retried, whatever failed`() {
        assertEquals(ApiFailure.LookupGivenUp, c(Endpoint.LOOKUP, 429, "rate_limited", retryAfter = 60))
        assertEquals(ApiFailure.LookupGivenUp, c(Endpoint.LOOKUP, null, null))
        assertEquals(ApiFailure.LookupGivenUp, c(Endpoint.LOOKUP, 500, "internal"))
    }

    @Test
    fun `401`() {
        assertEquals(ApiFailure.SignInFailed, c(Endpoint.EXCHANGE, 401, "unauthorized"))
        assertEquals(ApiFailure.SessionDead, c(Endpoint.CALL, 401, "unauthorized"))
    }

    @Test
    fun `403`() {
        assertEquals(ApiFailure.NoticeRequired, c(Endpoint.CALL, 403, "notice_required"))
        assertEquals(ApiFailure.Forbidden, c(Endpoint.CALL, 403, "forbidden", "key lacks the scope"))
        assertEquals(ApiFailure.Blocked, c(Endpoint.EXCHANGE, 403, "forbidden", "device_blocked"))
        assertEquals(ApiFailure.Refused(ApiFailure.Reason.NO_USER), c(Endpoint.EXCHANGE, 403, "forbidden", "no_user"))
        assertEquals(ApiFailure.Refused(ApiFailure.Reason.WRONG_DOMAIN), c(Endpoint.EXCHANGE, 403, "forbidden", "domain_not_allowed"))
        assertEquals(ApiFailure.Refused(ApiFailure.Reason.WRONG_DOMAIN), c(Endpoint.EXCHANGE, 403, "forbidden", "email_unverified"))
        assertEquals(ApiFailure.Refused(ApiFailure.Reason.INACTIVE), c(Endpoint.EXCHANGE, 403, "forbidden", "inactive"))
        assertEquals(ApiFailure.Refused(ApiFailure.Reason.ACCOUNT_MISMATCH), c(Endpoint.EXCHANGE, 403, "forbidden", "account_mismatch"))
    }

    @Test
    fun `404`() {
        assertEquals(ApiFailure.ServerTooOld, c(Endpoint.CALL, 404, "not_found", "unknown endpoint"))
        assertEquals(ApiFailure.CallGone, c(Endpoint.GET_CALL, 404, "not_found", "call 5099"))
        assertEquals(ApiFailure.CallGone, c(Endpoint.CONTENT, 404, "not_found", "call 5099"))
    }

    @Test
    fun `409`() {
        assertEquals(ApiFailure.Adopt, c(Endpoint.CALL, 409, "duplicate", "client_call_id already used with other call facts"))
        assertEquals(ApiFailure.Adopt, c(Endpoint.PICK, 409, "duplicate", "the call is not waiting for a deal"))
        assertEquals(ApiFailure.Adopt, c(Endpoint.PRIVATE, 409, "duplicate", "a logged call is edited or deleted in the CRM"))
        assertEquals(ApiFailure.NoticeChanged, c(Endpoint.ACKNOWLEDGE, 409, "conflict", "the notice version is not current"))
        assertEquals(ApiFailure.Retry, c(Endpoint.CALL, 409, "conflict", "this call is being written"))
    }

    @Test
    fun `400, 405, 426 and 429`() {
        assertEquals(ApiFailure.DropNext, c(Endpoint.CALL, 400, "invalid_input", "next.due_date must be on or after the call date"))
        assertEquals(ApiFailure.ClockAhead, c(Endpoint.CALL, 400, "invalid_input", "started_at: must not be in the future"))
        assertEquals(ApiFailure.Bug("Unrecognized key: \"colour\""), c(Endpoint.CALL, 400, "invalid_input", "Unrecognized key: \"colour\""))
        assertEquals(ApiFailure.Bug("method_not_allowed"), c(Endpoint.CALL, 405, "method_not_allowed"))
        assertEquals(ApiFailure.UpgradeRequired("minimum app version 1.4.0"), c(Endpoint.ME, 426, "upgrade_required", "minimum app version 1.4.0"))
        assertEquals(ApiFailure.RetryAfter(120), c(Endpoint.CONTENT, 429, "rate_limited", retryAfter = 120))
        assertEquals(ApiFailure.RetryAfter(600), c(Endpoint.CONTENT, 429, "rate_limited"))
    }
}
