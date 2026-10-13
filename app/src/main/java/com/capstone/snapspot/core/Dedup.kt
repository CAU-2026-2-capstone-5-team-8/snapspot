/**
 * Duplicate saves (issue #1): saving the same screenshot twice must not add the same spot twice.
 */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place

/** Two pins with the same name closer than this are the same place. */
const val SAME_PLACE_M = 50.0

fun findDuplicatePlace(existing: List<Place>, name: String, kakaoId: String?, at: Coords): Place? {
    val key = normalizeName(name)
    return existing.firstOrNull { p ->
        if (kakaoId != null && p.kakaoId != null) {
            p.kakaoId == kakaoId
        } else {
            key.isNotEmpty() && normalizeName(p.name) == key &&
                distanceMeters(at, Coords(p.latitude, p.longitude)) <= SAME_PLACE_M
        }
    }
}

private val SPACE = Regex("""\s+""")

private fun benefitKey(s: String) = s.lowercase().replace(SPACE, "")

/**
 * An unused coupon with the same brand, benefit and expiry. People can really hold two identical
 * gift cards, so the caller asks before adding another rather than refusing.
 */
fun findDuplicateCoupon(existing: List<Coupon>, brand: String, benefit: String, expiresAt: String?): Coupon? {
    val key = brandKey(brand)
    val b = benefitKey(benefit)
    return existing.firstOrNull {
        it.usedAt == null && brandKey(it.brand) == key && benefitKey(it.benefit) == b && it.expiresAt == expiresAt
    }
}
