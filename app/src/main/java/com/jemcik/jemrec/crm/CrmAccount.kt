package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.api.ApiFailure
import com.jemcik.jemrec.crm.api.ApiResult
import com.jemcik.jemrec.crm.api.Bodies
import com.jemcik.jemrec.crm.api.Notice
import com.jemcik.jemrec.crm.api.SalesEngineClient
import com.jemcik.jemrec.crm.db.WorkCallDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signing in to the Sales Engine and out of it, and the notice (§2, §3).
 *
 * @param device this phone as the exchange describes it (§2.2)
 */
class CrmAccount(
    private val client: SalesEngineClient,
    private val store: SessionStore,
    private val calls: WorkCallDao,
    private val device: Device,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Device(val label: String, val appVersion: String, val sdk: Int)

    sealed interface SignIn {
        data class Done(val name: String) : SignIn
        /** What to tell the seller, in plain words. */
        data class Refused(val message: String) : SignIn
        data object Blocked : SignIn
        data class UpgradeRequired(val minimum: String?) : SignIn
    }

    /** Exchanges a Google ID token for a session (§2.2). */
    suspend fun signIn(idToken: String): SignIn = withContext(Dispatchers.IO) {
        val body = Bodies.exchange(idToken, store.deviceId, device.label, device.appVersion, device.sdk)
        when (val r = client.exchange(body)) {
            is ApiResult.Ok -> {
                val s = r.value
                // §2.3: a phone that changed hands sends nothing queued for
                // the person before.
                val before = store.lastUserId
                if (before != null && before != s.user.id) calls.deleteOtherUsers(s.user.id)
                store.saveSession(s)
                if (AppVersion.isBelow(device.appVersion, s.minAppVersion)) {
                    store.setState(SessionStore.State.UPGRADE_REQUIRED)
                    SignIn.UpgradeRequired(s.minAppVersion)
                } else {
                    SignIn.Done(s.user.name)
                }
            }
            is ApiResult.Failed -> when (val f = r.failure) {
                ApiFailure.Blocked -> {
                    blocked()
                    SignIn.Blocked
                }
                is ApiFailure.UpgradeRequired -> {
                    store.setState(SessionStore.State.UPGRADE_REQUIRED)
                    SignIn.UpgradeRequired(f.detail?.substringAfterLast(' '))
                }
                else -> SignIn.Refused(message(f))
            }
        }
    }

    /** True when the session should be renewed: under 7 days left (§2.3). */
    fun needsRenewal(): Boolean {
        val s = store.session() ?: return false
        return store.state == SessionStore.State.ACTIVE && s.expiresAtMs - now() < RENEW_BEFORE_MS
    }

    /**
     * Signs out on purpose (§2.4): the server is told when it can be, and the
     * token is dropped here whatever it answers - a seller who taps Sign out
     * offline is signed out. Calls made from now on are never sent (FR-046).
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        if (store.token() != null) client.signOut()
        store.signedOut(now())
    }

    /** LANARS blocked this phone: the session and every queued call go (§2.2, FR-043). */
    suspend fun blocked() {
        calls.deleteAll()
        store.forgetAll()
    }

    suspend fun notice(): ApiResult<Notice> = withContext(Dispatchers.IO) { client.notice() }

    /** @return null when agreed; otherwise the failure (NoticeChanged: fetch it again and show it) */
    suspend fun acknowledge(version: String): ApiFailure? = withContext(Dispatchers.IO) {
        when (val r = client.acknowledge(version)) {
            is ApiResult.Ok -> {
                store.noticeAcknowledged(version, now())
                null
            }
            is ApiResult.Failed -> r.failure
        }
    }

    companion object {
        const val RENEW_BEFORE_MS = 7L * 24 * 60 * 60 * 1000

        /** §2.2's "What the user should see", and the rest in the same words. */
        fun message(f: ApiFailure): String = when (f) {
            ApiFailure.SignInFailed -> "Google's sign-in was not accepted. Try again."
            is ApiFailure.Refused -> when (f.reason) {
                ApiFailure.Reason.NO_USER -> "This account is not a Sales Engine user. Ask LANARS to add you."
                ApiFailure.Reason.WRONG_DOMAIN -> "Use your lanars.com or lanars.no account."
                ApiFailure.Reason.INACTIVE -> "Your Sales Engine account is inactive."
                ApiFailure.Reason.ACCOUNT_MISMATCH -> "This address belongs to another Google account. Contact LANARS."
                ApiFailure.Reason.OTHER -> "The Sales Engine refused this sign-in. Contact LANARS."
            }
            is ApiFailure.RetryAfter -> "Too many sign-ins today. Try again later."
            ApiFailure.Retry, ApiFailure.ServerTooOld -> "Could not reach the Sales Engine. Check the connection and try again."
            else -> "The sign-in did not work. Try again."
        }
    }
}
