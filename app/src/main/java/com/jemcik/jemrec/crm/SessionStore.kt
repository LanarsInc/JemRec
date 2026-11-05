package com.jemcik.jemrec.crm

import android.content.SharedPreferences
import androidx.core.content.edit
import com.jemcik.jemrec.crm.api.SessionData
import java.util.Locale
import java.util.UUID

/**
 * The Sales Engine session and setup, in the app's one preferences file
 * (Prefs) under keys of their own, `crm_...` (data-model.md).
 *
 * The token is kept sealed (TokenSealer); everything else is plain, and none
 * of it is a phone number or a call's words.
 */
class SessionStore(private val prefs: SharedPreferences, private val sealer: TokenSealer) {

    enum class State {
        /** Never signed in, or signed out on purpose. */
        SIGNED_OUT,
        ACTIVE,
        /** The token died by itself (expired, idle, revoked): calls wait for a sign-in. */
        NEEDS_SIGN_IN,
        /** The server wants a newer app: nothing goes until it is updated. */
        UPGRADE_REQUIRED,
        /** LANARS blocked this phone. */
        BLOCKED,
    }

    data class Session(
        val userId: Int,
        val userName: String,
        val userEmail: String,
        val expiresAtMs: Long,
        val tokenPrefix: String,
    )

    /** Made once per install and kept: it is this phone to the server (§2.2). */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().lowercase(Locale.ROOT).also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    val state: State
        get() = prefs.getString(KEY_STATE, null)?.let { s -> State.entries.firstOrNull { it.name == s } } ?: State.SIGNED_OUT

    fun setState(state: State) = prefs.edit { putString(KEY_STATE, state.name) }

    /** The token, while one is kept; null once signed out or when it cannot be opened. */
    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.let(sealer::open)

    fun session(): Session? {
        if (!prefs.contains(KEY_USER_ID)) return null
        return Session(
            userId = prefs.getInt(KEY_USER_ID, 0),
            userName = prefs.getString(KEY_USER_NAME, "") ?: "",
            userEmail = prefs.getString(KEY_USER_EMAIL, "") ?: "",
            expiresAtMs = prefs.getLong(KEY_EXPIRES, 0),
            tokenPrefix = prefs.getString(KEY_TOKEN_PREFIX, "") ?: "",
        )
    }

    /** The user the last session was for, kept after a sign-out to tell whether the next is another person. */
    val lastUserId: Int?
        get() = if (prefs.contains(KEY_USER_ID)) prefs.getInt(KEY_USER_ID, 0) else null

    fun saveSession(s: SessionData) {
        closeOutWindow()
        saveSessionFields(s)
    }

    private fun saveSessionFields(s: SessionData) = prefs.edit {
        putString(KEY_TOKEN, sealer.seal(s.token))
        putString(KEY_TOKEN_PREFIX, s.tokenPrefix)
        putLong(KEY_EXPIRES, s.expiresAtMs)
        putInt(KEY_USER_ID, s.user.id)
        putString(KEY_USER_NAME, s.user.name)
        putString(KEY_USER_EMAIL, s.user.email)
        if (s.minAppVersion == null) remove(KEY_MIN_VERSION) else putString(KEY_MIN_VERSION, s.minAppVersion)
        putString(KEY_NOTICE_CURRENT, s.noticeVersion)
        if (s.noticeAcknowledged) putString(KEY_NOTICE_ACKED, s.noticeVersion)
        putString(KEY_STATE, State.ACTIVE.name)
        remove(KEY_SIGNED_OUT_AT)
    }

    /** A token set by hand on the stand (debug only, §2.3). */
    fun saveTestToken(token: String, userId: Int, userName: String) {
        closeOutWindow()
        saveTestTokenFields(token, userId, userName)
    }

    private fun saveTestTokenFields(token: String, userId: Int, userName: String) = prefs.edit {
        putString(KEY_TOKEN, sealer.seal(token))
        putString(KEY_TOKEN_PREFIX, token.take(8))
        putLong(KEY_EXPIRES, Long.MAX_VALUE)
        putInt(KEY_USER_ID, userId)
        putString(KEY_USER_NAME, userName)
        putString(KEY_USER_EMAIL, "")
        putString(KEY_STATE, State.ACTIVE.name)
        remove(KEY_SIGNED_OUT_AT)
    }

    /** Signed out on purpose: calls made from now on are never sent (FR-046). */
    fun signedOut(nowMs: Long) = prefs.edit {
        remove(KEY_TOKEN)
        putString(KEY_STATE, State.SIGNED_OUT.name)
        putLong(KEY_SIGNED_OUT_AT, nowMs)
    }

    /**
     * The stretches the seller was signed out on purpose: calls in them are
     * never sent, even by a sweep after the next sign-in (FR-046). Kept as
     * "from-to;from-to", the latest few.
     */
    fun outWindows(): List<LongRange> = prefs.getString(KEY_OUT_WINDOWS, null).orEmpty().split(';').mapNotNull { w ->
        val (a, b) = w.split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
        val from = a.toLongOrNull() ?: return@mapNotNull null
        val to = b.toLongOrNull() ?: return@mapNotNull null
        from..to
    } + listOfNotNull(signedOutAtMs?.let { it..Long.MAX_VALUE })

    private fun closeOutWindow() {
        val from = signedOutAtMs ?: return
        val kept = prefs.getString(KEY_OUT_WINDOWS, null).orEmpty().split(';').filter { it.isNotBlank() }.takeLast(9)
        prefs.edit { putString(KEY_OUT_WINDOWS, (kept + "$from-${System.currentTimeMillis()}").joinToString(";")) }
    }

    /** When the seller last signed out on purpose, if they are still out. */
    val signedOutAtMs: Long?
        get() = if (prefs.contains(KEY_SIGNED_OUT_AT)) prefs.getLong(KEY_SIGNED_OUT_AT, 0) else null

    val minAppVersion: String? get() = prefs.getString(KEY_MIN_VERSION, null)

    fun setMinAppVersion(v: String?) = prefs.edit { if (v == null) remove(KEY_MIN_VERSION) else putString(KEY_MIN_VERSION, v) }

    // --- the notice (§3) ---

    val noticeAcknowledged: String? get() = prefs.getString(KEY_NOTICE_ACKED, null)
    val noticeCurrent: String? get() = prefs.getString(KEY_NOTICE_CURRENT, null)
    val noticeAcknowledgedAtMs: Long? get() = if (prefs.contains(KEY_NOTICE_AT)) prefs.getLong(KEY_NOTICE_AT, 0) else null

    /** True while the version this phone agreed to is the current one. */
    val noticeOk: Boolean get() = noticeAcknowledged != null && noticeAcknowledged == noticeCurrent

    fun noticeAcknowledged(version: String, atMs: Long) = prefs.edit {
        putString(KEY_NOTICE_ACKED, version)
        putString(KEY_NOTICE_CURRENT, version)
        putLong(KEY_NOTICE_AT, atMs)
    }

    /** The server says the notice changed (403 notice_required): agree again before anything goes. */
    fun noticeOutdated() = prefs.edit { putString(KEY_NOTICE_CURRENT, "") }

    // --- the work line and setup ---

    val workSubscriptionId: Int? get() = if (prefs.contains(KEY_LINE)) prefs.getInt(KEY_LINE, -1) else null

    fun setWorkLine(subscriptionId: Int) = prefs.edit { putInt(KEY_LINE, subscriptionId) }

    /** Calls before this are never taken (§13). Set the first time setup is complete, and kept. */
    val setupAtMs: Long? get() = if (prefs.contains(KEY_SETUP_AT)) prefs.getLong(KEY_SETUP_AT, 0) else null

    fun markSetUp(nowMs: Long) {
        if (!prefs.contains(KEY_SETUP_AT)) prefs.edit { putLong(KEY_SETUP_AT, nowMs) }
    }

    /** The call-log sweep's watermark: the highest `_ID` it has taken. */
    var lastCallLogId: Long
        get() = prefs.getLong(KEY_LAST_CALL_LOG_ID, 0)
        set(v) = prefs.edit { putLong(KEY_LAST_CALL_LOG_ID, v) }

    /** Everything the Sales Engine kept here, the device id included: a blocked phone starts over (§2.2). */
    fun forgetAll() = prefs.edit {
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach(::remove)
        putString(KEY_STATE, State.BLOCKED.name)
    }

    companion object {
        private const val PREFIX = "crm_"
        private const val KEY_DEVICE_ID = "crm_device_id"
        private const val KEY_STATE = "crm_state"
        private const val KEY_TOKEN = "crm_token"
        private const val KEY_TOKEN_PREFIX = "crm_token_prefix"
        private const val KEY_EXPIRES = "crm_expires_at"
        private const val KEY_USER_ID = "crm_user_id"
        private const val KEY_USER_NAME = "crm_user_name"
        private const val KEY_USER_EMAIL = "crm_user_email"
        private const val KEY_MIN_VERSION = "crm_min_app_version"
        private const val KEY_SIGNED_OUT_AT = "crm_signed_out_at"
        private const val KEY_OUT_WINDOWS = "crm_out_windows"
        private const val KEY_NOTICE_ACKED = "crm_notice_acknowledged"
        private const val KEY_NOTICE_CURRENT = "crm_notice_current"
        private const val KEY_NOTICE_AT = "crm_notice_at"
        private const val KEY_LINE = "crm_work_subscription"
        private const val KEY_SETUP_AT = "crm_setup_at"
        private const val KEY_LAST_CALL_LOG_ID = "crm_last_call_log_id"
    }
}
