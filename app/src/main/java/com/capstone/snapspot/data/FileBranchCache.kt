package com.capstone.snapspot.data

import com.capstone.snapspot.core.BranchCache
import com.capstone.snapspot.core.CACHE_TTL_MS
import com.capstone.snapspot.core.CachedBranches
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Branch search results per "brandKey|cell", in one file. Survives background wake-ups; old entries are dropped on write. */
class FileBranchCache(dir: File, private val clock: () -> Long = System::currentTimeMillis) : BranchCache {
    private val lock = Mutex()
    private val file = JsonFile(File(dir, "branches.json"), MapSerializer(String.serializer(), CachedBranches.serializer()))

    override suspend fun get(key: String): CachedBranches? = lock.withLock { file.read()?.get(key) }

    override suspend fun set(key: String, value: CachedBranches) = lock.withLock {
        val now = clock()
        val fresh = file.read().orEmpty().filterValues { now - it.at < CACHE_TTL_MS }
        file.write(fresh + (key to value))
    }
}
