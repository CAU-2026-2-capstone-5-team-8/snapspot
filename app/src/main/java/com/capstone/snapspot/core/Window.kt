/**
 * Sliding geofence window. Android watches at most ~100 regions, but a franchise coupon can match
 * hundreds of branches. So we register only what matters most near the user, plus one large
 * exit-only "refresh" region around them. Leaving it wakes the app to re-plan around the new
 * position — no continuous location tracking needed.
 *
 * Priority: saved places first (all of them), then brands with coupons about to expire, then the
 * other brands. Each brand gets at most its [BRANCHES_PER_BRAND] nearest branches, and brands in
 * the same tier take turns so one brand cannot fill the window alone.
 */
package com.capstone.snapspot.core

const val REFRESH_ID = "refresh"

/** A coupon only needs one alert at any branch, so the nearest few are enough. */
const val BRANCHES_PER_BRAND = 7

/** Earlier tiers fill the window first. */
enum class Tier { PLACE, URGENT, NORMAL }

/** Something worth watching: a saved place (group "place") or a branch (group = brand key). */
data class Spot(
    val id: String,
    val group: String,
    val tier: Tier,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    /** Order of the group within its tier (smaller first: the brand whose coupon expires sooner). */
    val rank: Int = 0,
)

data class Region(
    val identifier: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    val notifyOnEnter: Boolean,
    val notifyOnExit: Boolean,
)

data class Plan(val regions: List<Region>, val byGroup: Map<String, Int>)

fun planRegions(
    here: Coords?,
    spots: List<Spot>,
    /** Total regions the OS allows us; the refresh region counts toward it. */
    slots: Int,
    refreshRadius: Double,
    perGroupCap: Int = BRANCHES_PER_BRAND,
): Plan {
    val regions = mutableListOf<Region>()
    if (here != null && slots > 0) {
        regions += Region(REFRESH_ID, here.latitude, here.longitude, refreshRadius, notifyOnEnter = false, notifyOnExit = true)
    }

    // One queue per group, nearest first (input order when the position is unknown).
    val queues = LinkedHashMap<String, ArrayDeque<Spot>>()
    for (s in spots) queues.getOrPut(s.group) { ArrayDeque() }.addLast(s)
    if (here != null) {
        for ((g, q) in queues) queues[g] = ArrayDeque(q.sortedBy { distanceMeters(here, Coords(it.latitude, it.longitude)) })
    }

    // Read before the queues drain: a group's tier and rank come from its spots.
    val meta = queues.mapValues { (_, q) -> q.first().tier to q.first().rank }
    val byGroup = LinkedHashMap<String, Int>()
    for (tier in Tier.entries) {
        val order = meta.filter { it.value.first == tier }.entries.sortedBy { it.value.second }.map { it.key }
        val cap = if (tier == Tier.PLACE) Int.MAX_VALUE else perGroupCap
        var progressed = true
        while (regions.size < slots && progressed) {
            progressed = false
            for (g in order) {
                if (regions.size >= slots) break
                if ((byGroup[g] ?: 0) >= cap) continue
                val next = queues.getValue(g).removeFirstOrNull() ?: continue
                progressed = true
                byGroup[g] = (byGroup[g] ?: 0) + 1
                regions += Region(next.id, next.latitude, next.longitude, next.radius, notifyOnEnter = true, notifyOnExit = false)
            }
        }
    }
    return Plan(regions, byGroup)
}
