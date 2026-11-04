package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Branch
import com.capstone.snapspot.core.model.Place
import kotlin.coroutines.cancellation.CancellationException

const val MIN_ROUTE_PLACES = 2

/** The places for this open course only; the automatic bundle and saved places stay independent. */
data class RouteSelection(val bundleId: String, val placeIds: Set<String>)

/** Resolve against current saved data, ignoring deleted/ended places and keeping solver input stable. */
fun selectedRoutePlaces(places: List<Place>, ids: Set<String>, now: Long): List<Place> =
    places.filter { it.id in ids && !placeEnded(it, now) }.distinctBy { it.id }.sortedBy { it.id }

/**
 * An edited course can span several neighborhoods. Search each occupied cache cell, then apply
 * the usual 500m/8-branch rules against the selected places. A failed cell keeps other results.
 */
suspend fun routeCouponStops(
    places: List<Place>,
    brands: List<String>,
    search: suspend (String, Coords) -> List<Branch>,
): CouponFetch {
    if (places.isEmpty()) return CouponFetch(emptyList(), 0)
    val center = Coords(places.map { it.latitude }.average(), places.map { it.longitude }.average())
    val cells = places.map { Coords(it.latitude, it.longitude) }.distinctBy { cellOf(it).key }
    var failures = 0
    val found = nearbyCouponStops(places, center, brands) { brand ->
        var failed = false
        val branches = cells.flatMap { at ->
            try {
                search(brand, at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                emptyList()
            }
        }.distinctBy { it.kakaoId }
        if (failed) failures++
        branches
    }
    return found.copy(failed = failures)
}
