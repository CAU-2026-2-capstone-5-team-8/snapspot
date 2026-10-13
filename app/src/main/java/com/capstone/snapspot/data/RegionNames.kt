package com.capstone.snapspot.data

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.cellOf
import com.capstone.snapspot.core.regionDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Neighborhood display names per ~1km grid cell (region-names.json), so bundle alerts can name the
 * neighborhood without a network call. Not part of the team's data contract.
 */
class RegionNames(dir: File) {
    private val lock = Mutex()
    private val file = JsonFile(File(dir, "region-names.json"), MapSerializer(String.serializer(), String.serializer()))
    private val _names = MutableStateFlow(file.readNow().orEmpty())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    fun cached(at: Coords): String? = _names.value[cellOf(at).key]

    /** Cached name, or ask `lookup` (raw Kakao name) once and store the display name. Failures are not cached. */
    suspend fun resolve(at: Coords, lookup: suspend (Coords) -> String?): String? {
        cached(at)?.let { return it }
        val raw = try {
            lookup(at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val name = regionDisplayName(raw)
        lock.withLock {
            val next = file.read().orEmpty() + (cellOf(at).key to name)
            file.write(next)
            _names.value = next
        }
        return name
    }
}
