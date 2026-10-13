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

/**
 * `onScreen`: the app shows no notifications while open, and a held place up to 1km away is outside
 * its "지금 근처예요" radius, so an alert due now waits for the app to close instead of vanishing.
 */
fun decidePending(p: PendingAlert, fix: Fix?, now: Long, onScreen: Boolean = false): PendingDecision {
    if (now - p.since > PENDING_MAX_MS) return PendingDecision.DROP
    if (fix == null || isVehicleSpeed(fix.speed)) return PendingDecision.RECHECK
    val near = distanceMeters(fix.at, Coords(p.latitude, p.longitude)) <= PENDING_NEAR_M
    return when {
        !near -> PendingDecision.DROP
        onScreen -> PendingDecision.RECHECK
        else -> PendingDecision.ALERT
    }
}

/**
 * Adds `p`, folding it into a held alert of the same group: the latest target, the first `since`.
 * A held alert already past [PENDING_MAX_MS] (left behind if a recheck never ran) is replaced, so it
 * cannot hand its old start to a new pass.
 */
fun mergePending(list: List<PendingAlert>, p: PendingAlert): List<PendingAlert> {
    val old = list.firstOrNull { it.group == p.group } ?: return list + p
    val since = if (p.since - old.since > PENDING_MAX_MS) p.since else minOf(old.since, p.since)
    return list.map { if (it.group == p.group) p.copy(since = since) else it }
}
