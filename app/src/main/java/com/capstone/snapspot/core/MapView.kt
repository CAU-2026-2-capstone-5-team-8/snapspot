/**
 * Which gifticon branches the home map draws. The map looks branches up by itself around where it is looking,
 * apart from the alert plan (which only covers the user's surroundings), and draws only the nearest few.
 */
package com.capstone.snapspot.core

/** The map searches again once it looks this far from where it last searched. */
const val MAP_SEARCH_MOVE_M = 1500.0

/** Zoomed out further than this, the map covers too much to search: it asks to zoom in instead. */
const val MAP_MIN_SEARCH_ZOOM = 13

/** Branches are drawn only this close to the user (while the map follows them) or to the map's center. */
const val MAP_SHOW_RADIUS_M = 1000.0

/** At most this many branches of one brand are drawn, the nearest ones. */
const val MAP_SHOW_PER_BRAND = 5

fun needsMapSearch(last: Coords?, at: Coords): Boolean = last == null || distanceMeters(last, at) > MAP_SEARCH_MOVE_M

/**
 * Ids of the branches worth drawing around `center`: within `radiusM`, and the `perBrand` nearest of each brand
 * (the brand is read from the id, `b|brand|kakaoId`). Without a center nothing is left out.
 */
fun limitBranches(
    pins: Map<String, Coords>,
    center: Coords?,
    radiusM: Double = MAP_SHOW_RADIUS_M,
    perBrand: Int = MAP_SHOW_PER_BRAND,
): Set<String> {
    if (center == null) return pins.keys
    return pins.map { (id, at) -> id to distanceMeters(center, at) }
        .filter { it.second <= radiusM }
        .groupBy { brandKeyOfRegion(it.first) }
        .values.flatMap { g -> g.sortedBy { it.second }.take(perBrand).map { it.first } }
        .toSet()
}
