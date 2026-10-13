package com.capstone.snapspot.geofence

import com.capstone.snapspot.core.BranchCache
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.KakaoApi
import com.capstone.snapspot.core.MAX_REGIONS
import com.capstone.snapspot.core.Plan
import com.capstone.snapspot.core.REFRESH_RADIUS_M
import com.capstone.snapspot.core.Spot
import com.capstone.snapspot.core.Tier
import com.capstone.snapspot.core.brandGroups
import com.capstone.snapspot.core.branchSpots
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.nearbyBranches
import com.capstone.snapspot.core.placeSpots
import com.capstone.snapspot.core.planRegions
import com.capstone.snapspot.data.WindowBranch
import com.capstone.snapspot.data.WindowState
import kotlin.coroutines.cancellation.CancellationException

data class WindowResult(val plan: Plan, val state: WindowState)

/**
 * Saved data + position → the regions to register and the state to remember. No Android here,
 * so tests drive it with a fake Kakao. Branches are searched only when the position is known.
 */
suspend fun buildWindow(
    here: Coords?,
    places: List<Place>,
    coupons: List<Coupon>,
    api: KakaoApi,
    cache: BranchCache,
    now: Long,
): WindowResult {
    val spots = placeSpots(places, now).toMutableList<Spot>()
    val branchInfo = HashMap<String, WindowBranch>()
    var requests = 0
    if (here != null) {
        for (g in brandGroups(coupons, now)) {
            try {
                val r = nearbyBranches(g.brand, here, api, cache, now)
                requests += r.requests
                val branchSpots = branchSpots(g, r.branches)
                spots += branchSpots
                r.branches.zip(branchSpots).forEach { (b, s) ->
                    branchInfo[s.id] = WindowBranch(b.name, g.key, b.latitude, b.longitude, g.tier == Tier.URGENT)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One brand failing (network, quota) must not drop the places and other brands.
            }
        }
    }
    val plan = planRegions(here, spots, MAX_REGIONS, REFRESH_RADIUS_M)
    val registered = plan.regions.map { it.identifier }.toSet()
    val state = WindowState(
        at = now,
        here = here,
        slots = MAX_REGIONS,
        byGroup = plan.byGroup,
        branches = branchInfo.filterKeys { it in registered },
        requests = requests,
    )
    return WindowResult(plan, state)
}
