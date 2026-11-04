package com.jemcik.jemrec.crm.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One phone call on the work line, from the moment the call log shows it until
 * the Sales Engine has all it will get (specs/002, data-model.md).
 *
 * Enums are kept as their names, so a renamed state is a migration rather than
 * a silent zero; JSON columns hold the server's own shapes (ApiModels), which
 * nothing queries.
 */
@Entity(
    tableName = "work_call",
    indices = [
        Index(value = ["clientCallId"], unique = true),
        Index(value = ["callLogId"], unique = true),
        Index(value = ["recording"]),
    ],
)
data class WorkCallEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** UUID v4, lower case, made once: the call's key for every request (§6.3). */
    val clientCallId: String,
    /** The call log row's `_ID`, so a second sweep never takes the row again. */
    val callLogId: Long,
    /** Who it was queued for; another person's sign-in drops it (FR-044). */
    val userId: Int,
    val outgoing: Boolean,
    val answered: Boolean,
    /** As the call log has it; null for a hidden number. Never logged or shown in diagnostics. */
    val phone: String?,
    /** `Presentation` name. */
    val presentation: String,
    val countryIso: String?,
    val startedAtMs: Long,
    val durationSeconds: Int,
    /** When the call ended by the call log's account: start plus its length. */
    val endedAtMs: Long,
    /** The recording's file name, once one was matched. */
    val recording: String? = null,
    /** The answering machine took it (FR-017). */
    val byMachine: Boolean = false,
    /** The lookup's answer as JSON (ApiModels.lookupJson), or null: not made, or failed. */
    val lookupJson: String? = null,
    /** `DealChoice` name. */
    val choice: String = "AUTO",
    val dealId: Int? = null,
    /** Null until it matters: true when the seller picked, false when the lookup's single deal was kept. */
    val chosenByUser: Boolean? = null,
    /** `Outcome` name. */
    val outcome: String,
    /** NextCall as JSON. */
    val nextJson: String? = null,
    /** `WorkCallMachine.Local` name. */
    val localState: String,
    /** Built once and resent unchanged (§6.3). */
    val stepOneBody: String? = null,
    /** Nothing is done for this call before then: the hold after the call, a backoff, a `retry-after`. */
    val dueAtMs: Long,
    val serverId: Int? = null,
    /** The last call object the server sent (ApiModels.callJson). */
    val serverJson: String? = null,
    /** The seller's pick, waiting to be sent: `DealChoice` name, with [pickDealId] and [pickNextJson]. */
    val pickChoice: String? = null,
    val pickDealId: Int? = null,
    val pickNextJson: String? = null,
    /** `WorkCallMachine.Content` name. */
    val contentState: String,
    /** The step-2 body, made once from the first finished transcript (FR-035). */
    val contentBody: String? = null,
    val contentDueAtMs: Long = 0,
    val attempts: Int = 0,
    /** For the screen and diagnostics: an outcome's name or the server's `detail`; never a number or text. */
    val lastError: String? = null,
)
