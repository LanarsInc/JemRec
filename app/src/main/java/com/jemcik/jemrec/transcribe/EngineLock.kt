package com.jemcik.jemrec.transcribe

import kotlinx.coroutines.sync.Mutex

/**
 * One heavy model in memory at a time.
 *
 * Transcription (whisper.cpp) and summaries (llama.cpp) each load a model of
 * hundreds of megabytes to gigabytes; on a 6 GB phone like the Galaxy A24 the
 * two together would leave Android little enough that it kills the app in the
 * background. And two jobs fighting for the same cores are no faster than one
 * after the other. Both engines take this lock around load, run and free.
 */
object EngineLock {
    val mutex = Mutex()
}
