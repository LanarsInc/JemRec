package com.jemcik.jemrec.crm.api

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ApiResult<out T> {
    /** @param created 201 rather than 200: step 1 stored the call just now (§6.2). */
    data class Ok<T>(val value: T, val created: Boolean = false) : ApiResult<T>
    data class Failed(val failure: ApiFailure) : ApiResult<Nothing>
}

/**
 * The Sales Engine's API for the Android call app, one function per endpoint
 * (specs/002, contracts/api-client.md). Plain JDK - `HttpURLConnection` and
 * `org.json` - so it runs under a JVM test against a real local server
 * (research R1).
 *
 * Blocking: called from the sync worker's thread, one request at a time.
 *
 * @param baseUrl e.g. `https://crm.lanars.no/api/v1`, no trailing slash
 * @param token the current token, read on every request so a fresh sign-in
 *   takes effect at once
 */
class SalesEngineClient(
    private val baseUrl: String,
    private val appVersion: String,
    private val token: () -> String?,
) {

    fun exchange(body: String): ApiResult<SessionData> =
        send(Endpoint.EXCHANGE, "POST", "/mobile/session", body, auth = false) { ApiModels.session(it) }

    /** A 401 means the token had already stopped: signed out either way (§2.4). */
    fun signOut(): ApiResult<Unit> = when (val r = send(Endpoint.SIGN_OUT, "DELETE", "/mobile/session", null) { }) {
        is ApiResult.Failed -> if (r.failure == ApiFailure.SessionDead) ApiResult.Ok(Unit) else r
        else -> r
    }

    fun notice(): ApiResult<Notice> = send(Endpoint.NOTICE, "GET", "/mobile/notice", null) { ApiModels.notice(it) }

    fun acknowledge(version: String): ApiResult<String> =
        send(Endpoint.ACKNOWLEDGE, "POST", "/mobile/notice", Bodies.acknowledge(version)) { it.getString("acknowledged_at") }

    fun me(): ApiResult<UserInfo> = send(Endpoint.ME, "GET", "/me", null) { ApiModels.user(it.getJSONObject("user")) }

    fun lookup(phone: String, countryIso: String?): ApiResult<Lookup> =
        send(Endpoint.LOOKUP, "POST", "/calls/lookup", Bodies.lookup(phone, countryIso)) { ApiModels.lookup(it) }

    fun postCall(body: String): ApiResult<CallState> = send(Endpoint.CALL, "POST", "/calls", body) { ApiModels.call(it) }

    fun pickDeal(callId: Int, body: String): ApiResult<CallState> =
        send(Endpoint.PICK, "POST", "/calls/$callId/deal", body) { ApiModels.call(it) }

    fun postContent(callId: Int, body: String): ApiResult<CallState> =
        send(Endpoint.CONTENT, "POST", "/calls/$callId/content", body) { ApiModels.call(it) }

    fun getCall(callId: Int): ApiResult<CallState> = send(Endpoint.GET_CALL, "GET", "/calls/$callId", null) { ApiModels.call(it) }

    fun makePrivate(callId: Int): ApiResult<CallState> =
        send(Endpoint.PRIVATE, "POST", "/calls/$callId/private", null) { ApiModels.call(it) }

    private fun <T> send(
        endpoint: Endpoint,
        method: String,
        path: String,
        body: String?,
        auth: Boolean = true,
        parse: (JSONObject) -> T,
    ): ApiResult<T> {
        val bearer = if (auth) token() ?: return ApiResult.Failed(ApiFailure.SessionDead) else null
        val connection = try {
            URL(baseUrl + path).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return failed(endpoint, null, null)
        }
        try {
            connection.requestMethod = method
            // §1: no redirects; a 3xx is an error, and following one could
            // hand the token to another host.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("accept", "application/json")
            connection.setRequestProperty("x-app-version", appVersion)
            if (bearer != null) connection.setRequestProperty("x-api-token", bearer)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("content-type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = text?.let { parseObject(it) }
            if (status in 200..299) {
                val data = json?.optJSONObject("data") ?: return failed(endpoint, null, null)
                return try {
                    ApiResult.Ok(parse(data), created = status == 201)
                } catch (_: JSONException) {
                    // A 2xx whose data is not the shape the document gives: as
                    // good as a cut body - retry later rather than act on half.
                    failed(endpoint, null, null)
                }
            }
            val error = json?.optStringOrNull("error")
            return failed(
                endpoint, status, error, json?.optStringOrNull("detail"),
                connection.getHeaderField("retry-after")?.trim()?.toLongOrNull(),
            )
        } catch (_: IOException) {
            return failed(endpoint, null, null)
        } finally {
            connection.disconnect()
        }
    }

    private fun failed(endpoint: Endpoint, status: Int?, error: String?, detail: String? = null, retryAfter: Long? = null) =
        ApiResult.Failed(ApiErrors.classify(endpoint, status, error, detail, retryAfter))

    private fun parseObject(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
