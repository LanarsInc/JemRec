/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.jemcik.jemrec.transcribe.model

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per model file, shared by every job in this process that writes into the models folder.
 *
 * Moved out of [ModelDownloadWorker] so that importing a model from storage (GitHub #58) takes the
 * same lock: a download and an import of the same model must never write, verify or rename the same
 * file at once, and a stopped attempt that is still inside a blocking read must finish letting go of
 * the file before the next one starts.
 */
internal object ModelWriters {
    private val writers = ConcurrentHashMap<String, Mutex>()

    /** The lock for [model]'s file, keyed by file name so models sharing a file share a lock. */
    fun forModel(model: DownloadableModel): Mutex = writers.getOrPut(model.fileName) { Mutex() }
}
