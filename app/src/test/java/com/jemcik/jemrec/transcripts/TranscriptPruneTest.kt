package com.jemcik.jemrec.transcripts

import com.jemcik.jemrec.transcripts.db.StoredTranscript
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which transcripts go when their recordings are no longer listed. */
class TranscriptPruneTest {

    private val now = 1_000_000_000L
    private val grace = 600_000L
    private val old = now - 2 * grace

    @Test fun aTranscriptWhoseRecordingIsGoneGoes() {
        val stored = listOf(StoredTranscript("gone.ogg", old), StoredTranscript("here.ogg", old))
        assertEquals(listOf("gone.ogg"), TranscriptPrune.toDrop(stored, setOf("here.ogg"), now, grace))
    }

    /** Transcribed just now: its recording may be newer than the list. */
    @Test fun aNewTranscriptStaysUntilTheListCatchesUp() {
        val stored = listOf(StoredTranscript("new.ogg", now - 1_000))
        assertEquals(emptyList<String>(), TranscriptPrune.toDrop(stored, setOf("other.ogg"), now, grace))
    }

    /** An empty list is as likely a missing permission as an empty phone: nothing goes. */
    @Test fun anEmptyListDropsNothing() {
        val stored = listOf(StoredTranscript("a.ogg", old))
        assertEquals(emptyList<String>(), TranscriptPrune.toDrop(stored, emptySet(), now, grace))
    }
}
