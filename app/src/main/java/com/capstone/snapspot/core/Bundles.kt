/**
 * Neighborhood bundles: saved places within walking distance of each other, recomputed from the
 * saved places every time (never stored). Single linkage: places 700m apart chain together.
 */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Place

const val BUNDLE_LINK_M = 700.0
const val BUNDLE_MIN = 3

/** `id` is the smallest member id, so it stays put while the members do. */
data class Bundle(val id: String, val places: List<Place>) {
    val center: Coords get() = Coords(places.map { it.latitude }.average(), places.map { it.longitude }.average())
}

fun bundles(places: List<Place>, now: Long, here: Coords? = null): List<Bundle> {
    val live = places.filterNot { placeEnded(it, now) }
    val parent = IntArray(live.size) { it }
    fun root(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        return r
    }
    for (i in live.indices) for (j in i + 1 until live.size) {
        val d = distanceMeters(Coords(live[i].latitude, live[i].longitude), Coords(live[j].latitude, live[j].longitude))
        if (d <= BUNDLE_LINK_M) parent[root(i)] = root(j)
    }
    val groups = live.indices.groupBy { root(it) }.values
        .filter { it.size >= BUNDLE_MIN }
        .map { idx -> idx.map { live[it] }.sortedBy { it.id } }
        .map { Bundle(it.first().id, it) }
    return if (here != null) groups.sortedBy { distanceMeters(here, it.center) }
    else groups.sortedWith(compareByDescending<Bundle> { it.places.size }.thenBy { it.id })
}

fun bundleOf(places: List<Place>, placeId: String, now: Long): Bundle? =
    bundles(places, now).firstOrNull { b -> b.places.any { it.id == placeId } }

private val NUMBERED = Regex("""\d+(·\d+)*가\d*동$|\d+동$""")

/** "성수2가1동" → "성수동": administrative dong names as people say them. */
fun regionDisplayName(raw: String): String {
    val name = raw.replace(NUMBERED, "동")
    return if (name == "동") raw else name
}

private fun Place.at() = Coords(latitude, longitude)

/** When Kakao gave no name: the place nearest the bundle's center. */
fun fallbackBundleName(b: Bundle): String = "${b.places.minBy { distanceMeters(it.at(), b.center) }.name} 근처"

/** No walking time or route here: those live on the home screen. */
fun bundleAlertText(name: String, b: Bundle, enteredId: String): Pair<String, String> {
    val entered = b.places.first { it.id == enteredId }
    val rest = b.places.filter { it.id != enteredId }.sortedBy { distanceMeters(entered.at(), it.at()) }
    val body = "${entered.name}, ${rest.first().name}" + if (b.places.size > 2) " 외 ${b.places.size - 2}곳" else ""
    return "📍 ${name}에 가 볼 곳 ${b.places.size}곳이 있어요" to body
}
