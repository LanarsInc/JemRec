package com.jemcik.jemrec.transcripts.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * The summary of one recording's transcript.
 *
 * [json] is CallSummary.toJson(). [searchText] is CallSummary.searchableText():
 * the words alone, without the JSON's keys - indexing the JSON would make
 * "summary" or "decisions" match every call that has one (CallVault's finding).
 */
@Entity(tableName = "call_summaries")
data class CallSummaryEntry(
    @PrimaryKey val recording: String,
    val modelId: String,
    /** The language it was written in. */
    val language: String,
    val json: String,
    val searchText: String,
    val wallMs: Long,
    val createdAt: Long,
)

@Fts4(contentEntity = CallSummaryEntry::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "call_summaries_fts")
data class CallSummaryFts(val searchText: String)
