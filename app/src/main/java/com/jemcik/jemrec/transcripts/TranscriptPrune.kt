package com.jemcik.jemrec.transcripts

import com.jemcik.jemrec.transcripts.db.StoredTranscript

/** Which transcripts have lost their recording: the decision behind TranscriptRepository.prune. */
object TranscriptPrune {

    /**
     * The recordings in [stored] that are not in [listed] and were
     * transcribed more than [graceMs] before [now]. An empty [listed] drops
     * nothing: a list read without the audio permission is empty too, and
     * reading it as "every recording is gone" would delete every transcript.
     */
    fun toDrop(stored: List<StoredTranscript>, listed: Set<String>, now: Long, graceMs: Long): List<String> {
        if (listed.isEmpty()) return emptyList()
        return stored.filter { it.recording !in listed && now - it.createdAt > graceMs }.map { it.recording }
    }
}
