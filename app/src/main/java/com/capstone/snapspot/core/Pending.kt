/**
 * Alerts held back while the user is in a vehicle. Passing a saved place on a bus is not a moment
 * anyone can act on; getting off nearby and walking is. Pure, so tests cover every rule.
 */
package com.capstone.snapspot.core

import kotlinx.serialization.Serializable

/** 15 km/h. Walking is about 1.4 m/s; city buses average 5–8 m/s. */
const val VEHICLE_SPEED_MPS = 4.2f

const val RECHECK_DELAY_MS = 3 * 60_000L

/** Counted from the first hold; after this the alert is dropped. */
const val PENDING_MAX_MS = 30 * 60_000L

/** Got off within this distance of the held place or branch: worth an alert. */
const val PENDING_NEAR_M = 1000.0

/** Unknown speed (Wi-Fi or cell fix) counts as walking: one extra alert beats a missed one. */
fun isVehicleSpeed(speed: Float?): Boolean = speed != null && speed >= VEHICLE_SPEED_MPS

/** A position with the speed it was moving at, if known (m/s). */
data class Fix(val at: Coords, val speed: Float?)

@Serializable
data class PendingAlert(
    /** A place id, or a branch region id "b|<brandKey>|<kakaoId>". */
    val regionId: String,
    /** One held alert per group: the place id, or the brand key for coupons. */
    val group: String,
    val latitude: Double,
    val longitude: Double,
    /** When it was first held; [PENDING_MAX_MS] runs from here. */
    val since: Long,
)

enum class PendingDecision { ALERT, RECHECK, DROP }

fun decidePending(p: PendingAlert, fix: Fix?, now: Long): PendingDecision {
    if (now - p.since > PENDING_MAX_MS) return PendingDecision.DROP
    if (fix == null || isVehicleSpeed(fix.speed)) return PendingDecision.RECHECK
    val near = distanceMeters(fix.at, Coords(p.latitude, p.longitude)) <= PENDING_NEAR_M
    return if (near) PendingDecision.ALERT else PendingDecision.DROP
}

/** Adds `p`, folding it into a held alert of the same group: the latest target, the first `since`. */
fun mergePending(list: List<PendingAlert>, p: PendingAlert): List<PendingAlert> {
    val old = list.firstOrNull { it.group == p.group } ?: return list + p
    return list.map { if (it.group == p.group) p.copy(since = minOf(old.since, p.since)) else it }
}
