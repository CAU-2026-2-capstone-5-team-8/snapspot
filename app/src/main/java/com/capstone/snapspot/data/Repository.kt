package com.capstone.snapspot.data

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** The geofence plan currently registered with the OS: for alerts woken in the background and the status screen. */
@Serializable
data class WindowState(
    val at: Long,
    val here: Coords?,
    val slots: Int,
    /** Regions registered per group: "place" or a brand key. */
    val byGroup: Map<String, Int>,
    /** Branch regions by identifier, so an Enter event can name the branch and the map can draw it. */
    val branches: Map<String, WindowBranch>,
    /** Kakao requests made by this plan (0 when every brand came from cache). */
    val requests: Int,
)

@Serializable
data class WindowBranch(
    val name: String,
    val brandKey: String,
    val latitude: Double,
    val longitude: Double,
    /** The brand has a coupon expiring within 3 days. */
    val urgent: Boolean,
)

internal val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A JSON file holding one value. Unreadable files read as `null` so a bad write cannot brick the app. */
internal class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    fun read(): T? = runCatching { if (file.exists()) storeJson.decodeFromString(serializer, file.readText()) else null }.getOrNull()

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(storeJson.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Saved places, coupons and the current geofence plan, shared by the screens and the background
 * workers. Every write is load → change → save under one lock, so a geofence alert and a save on
 * screen cannot overwrite each other.
 */
class Repository(dir: File) {
    private val lock = Mutex()
    private val placesFile = JsonFile(File(dir, "places.json"), ListSerializer(Place.serializer()))
    private val couponsFile = JsonFile(File(dir, "coupons.json"), ListSerializer(Coupon.serializer()))
    private val windowFile = JsonFile(File(dir, "window.json"), WindowState.serializer())

    private val _places = MutableStateFlow(placesFile.read().orEmpty())
    private val _coupons = MutableStateFlow(couponsFile.read().orEmpty())
    private val _window = MutableStateFlow(windowFile.read())
    val places: StateFlow<List<Place>> = _places.asStateFlow()
    val coupons: StateFlow<List<Coupon>> = _coupons.asStateFlow()
    val window: StateFlow<WindowState?> = _window.asStateFlow()

    /** Re-read from disk: another process (a background worker) may have written. */
    suspend fun reload() = lock.withLock {
        _places.value = placesFile.read().orEmpty()
        _coupons.value = couponsFile.read().orEmpty()
        _window.value = windowFile.read()
    }

    private suspend fun editPlaces(f: (List<Place>) -> List<Place>) = lock.withLock {
        val next = f(placesFile.read().orEmpty())
        placesFile.write(next)
        _places.value = next
    }

    private suspend fun editCoupons(f: (List<Coupon>) -> List<Coupon>) = lock.withLock {
        val next = f(couponsFile.read().orEmpty())
        couponsFile.write(next)
        _coupons.value = next
    }

    suspend fun upsertPlace(p: Place) = editPlaces { list -> listOf(p) + list.filter { it.id != p.id } }
    suspend fun removePlace(id: String) = editPlaces { list -> list.filter { it.id != id } }
    suspend fun markPlaceNotified(id: String, at: Long) =
        editPlaces { list -> list.map { if (it.id == id) it.copy(lastNotifiedAt = at) else it } }

    suspend fun upsertCoupon(c: Coupon) = editCoupons { list -> listOf(c) + list.filter { it.id != c.id } }
    suspend fun removeCoupon(id: String) = editCoupons { list -> list.filter { it.id != id } }
    suspend fun updateCoupons(ids: List<String>, f: (Coupon) -> Coupon) =
        editCoupons { list -> list.map { if (it.id in ids) f(it) else it } }

    suspend fun saveWindow(w: WindowState) = lock.withLock {
        windowFile.write(w)
        _window.value = w
    }
}
