package com.jemcik.jemrec.crm

/**
 * Where the Sales Engine stands on this phone, in the order the seller deals
 * with it: one answer, for the Settings row and for whether anything may be
 * sent at all. Pure.
 */
enum class CrmState(val line: String) {
    NOT_CONFIGURED("Not set up in this build"),
    NOT_CONNECTED("Not connected"),
    BLOCKED("This phone was blocked by LANARS"),
    UPGRADE_REQUIRED("Update the app to keep logging calls"),
    NEEDS_SIGN_IN("Sign in again"),
    NEEDS_NOTICE("Read and agree to the notice"),
    NEEDS_CALL_LOG("Allow the call log"),
    NEEDS_LINE("Choose the work line"),
    CONNECTED("Connected"),
    ;

    /** Requests go out only when everything is in place. */
    val sends: Boolean get() = this == CONNECTED

    /**
     * Calls are still taken into the queue while the session or the notice
     * waits on the seller: they go once it is fixed (§2.3, §3 "calls wait
     * until it has"). Not after a sign-out on purpose or a block.
     */
    val queues: Boolean get() = this == CONNECTED || this == NEEDS_SIGN_IN || this == NEEDS_NOTICE || this == UPGRADE_REQUIRED

    /** The seller has something to do here; the Settings row says it in red. */
    val needsSeller: Boolean get() = this != CONNECTED && this != NOT_CONFIGURED && this != NOT_CONNECTED

    companion object {
        fun of(
            configured: Boolean,
            session: SessionStore.State,
            noticeOk: Boolean,
            callLogAllowed: Boolean,
            lineChosen: Boolean,
        ): CrmState = when {
            !configured -> NOT_CONFIGURED
            session == SessionStore.State.BLOCKED -> BLOCKED
            session == SessionStore.State.SIGNED_OUT -> NOT_CONNECTED
            session == SessionStore.State.UPGRADE_REQUIRED -> UPGRADE_REQUIRED
            session == SessionStore.State.NEEDS_SIGN_IN -> NEEDS_SIGN_IN
            !noticeOk -> NEEDS_NOTICE
            !callLogAllowed -> NEEDS_CALL_LOG
            !lineChosen -> NEEDS_LINE
            else -> CONNECTED
        }
    }
}
