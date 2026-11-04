package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Branch
import com.capstone.snapspot.core.model.Place
import kotlin.coroutines.cancellation.CancellationException

const val MIN_ROUTE_PLACES = 2

/** The places for this open course only; the automatic bundle and saved places stay independent. */
data class RouteSelection(val bundleId: String, val placeIds: Set<String>, val orderedPlaceIds: List<String> = emptyList())

/** Resolve against current saved data, ignoring deleted/ended places and keeping solver input stable. */
fun selectedRoutePlaces(places: List<Place>, ids: Set<String>, now: Long): List<Place> =
    places.filter { it.id in ids && !placeEnded(it, now) }.distinctBy { it.id }.sortedBy { it.id }

/** Keep a chosen first place; otherwise recommend the nearest selected place by straight-line distance. */
fun firstRoutePlace(places: List<Place>, preferredId: String?, here: Coords?): Place? =
    places.firstOrNull { it.id == preferredId }
        ?: here?.let { at ->
            places.minWithOrNull(compareBy<Place> { distanceMeters(at, Coords(it.latitude, it.longitude)) }.thenBy { it.id })
        }

/** Preview saved-place order locally; the nearest place leads when the user's position is known. */
fun recommendRouteOrder(places: List<Place>, here: Coords?): List<String> {
    val stable = places.distinctBy { it.id }.sortedBy { it.id }
    val first = firstRoutePlace(stable, null, here)?.let { place -> stable.indexOfFirst { it.id == place.id } }
    return routeWithFirstPlace(stopsOf(stable.map { Coords(it.latitude, it.longitude) }), here, first).order.map { stable[it].id }
}

/** Move a selected place to another selected place's slot; membership stays unchanged. */
fun moveRoutePlace(order: List<String>, id: String, target: String): List<String> {
    val from = order.indexOf(id)
    val to = order.indexOf(target)
    if (from < 0 || to < 0 || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

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
