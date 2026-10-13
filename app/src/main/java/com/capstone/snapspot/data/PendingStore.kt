package com.capstone.snapspot.data

import com.capstone.snapspot.core.PendingAlert
import com.capstone.snapspot.core.mergePending
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Alerts held while the user was in a vehicle (pending.json). Kept out of [Repository] on purpose:
 * that file is the team's data contract, and this is background bookkeeping.
 */
class PendingStore(dir: File) {
    private val lock = Mutex()
    private val file = JsonFile(File(dir, "pending.json"), ListSerializer(PendingAlert.serializer()))

    suspend fun all(): List<PendingAlert> = lock.withLock { file.read().orEmpty() }

    suspend fun add(p: PendingAlert) = lock.withLock { file.write(mergePending(file.read().orEmpty(), p)) }

    /**
     * Removes exactly these settled entries. A hold merged in after they were read has a different
     * target or start, so it stays.
     */
    suspend fun remove(settled: List<PendingAlert>) = lock.withLock {
        file.write(file.read().orEmpty().filterNot { it in settled })
    }
}
