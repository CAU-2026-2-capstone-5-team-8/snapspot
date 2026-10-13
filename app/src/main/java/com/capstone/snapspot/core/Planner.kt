/** Saved places and coupons → the spots the geofence window chooses from. */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Branch
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place

/** OS limit is 100 on Android; keep a margin. */
const val MAX_REGIONS = 90

/** Branch alert radius. Geofence alerts arrive minutes late, so err on the wide side. */
const val BRANCH_RADIUS_M = 200.0

/** Leaving this circle around the user triggers a re-plan. */
const val REFRESH_RADIUS_M = 1000.0

const val PLACE_GROUP = "place"

fun placeSpots(places: List<Place>, now: Long): List<Spot> =
    places.filterNot { placeEnded(it, now) }.map {
        Spot(it.id, PLACE_GROUP, Tier.PLACE, it.latitude, it.longitude, it.radius.toDouble())
    }

/** One brand with active coupons. `brand` is the query sent to Kakao. */
data class BrandGroup(val key: String, val brand: String, val tier: Tier, val rank: Int)

/** Brands with active coupons, soonest expiry first (no expiry last); urgent if any coupon is. */
fun brandGroups(coupons: List<Coupon>, now: Long): List<BrandGroup> =
    coupons.filter { isActive(it, now) }
        .groupBy { brandKey(it.brand) }
        .map { (key, list) ->
            val first = list.minBy { it.expiresAt ?: "9999" }
            Triple(key, first, list.any { isUrgent(it, now) })
        }
        .sortedBy { it.second.expiresAt ?: "9999" }
        .mapIndexed { i, (key, first, urgent) ->
            BrandGroup(key, first.brand, if (urgent) Tier.URGENT else Tier.NORMAL, i)
        }

fun branchSpotId(key: String, kakaoId: String) = "b|$key|$kakaoId"

fun isBranchRegion(id: String) = id.startsWith("b|")

/** "b|<brandKey>|<kakaoId>" → brandKey. */
fun brandKeyOfRegion(id: String): String = id.split("|").getOrElse(1) { "" }

fun branchSpots(g: BrandGroup, branches: List<Branch>): List<Spot> =
    branches.map { Spot(branchSpotId(g.key, it.kakaoId), g.key, g.tier, it.latitude, it.longitude, BRANCH_RADIUS_M, g.rank) }
