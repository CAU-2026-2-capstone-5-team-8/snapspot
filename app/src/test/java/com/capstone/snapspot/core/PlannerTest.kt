package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlannerTest {
    @Test
    fun `ended places are not watched`() {
        val spots = placeSpots(listOf(place(id = "open"), place(id = "ended", endDate = "2026-10-01")), NOW)
        assertEquals(listOf("open"), spots.map { it.id })
        assertEquals(Tier.PLACE, spots.single().tier)
        assertEquals("place", spots.single().group)
    }

    @Test
    fun `used and expired coupons do not bring their brand in`() {
        val groups = brandGroups(
            listOf(
                coupon(brand = "스타벅스", usedAt = 1),
                coupon(brand = "올리브영", expiresAt = "2026-10-01"),
                coupon(brand = "메가커피"),
            ),
            NOW,
        )
        assertEquals(listOf("메가커피"), groups.map { it.brand })
    }

    @Test
    fun `two coupons of one brand make one group, urgent if any coupon is urgent`() {
        val groups = brandGroups(
            listOf(coupon(id = "a", brand = "스타벅스", expiresAt = "2026-12-31"), coupon(id = "b", brand = "스타 벅스", expiresAt = "2026-10-12")),
            NOW,
        )
        assertEquals(1, groups.size)
        assertEquals(Tier.URGENT, groups.single().tier)
        assertEquals(brandKey("스타벅스"), groups.single().key)
    }

    @Test
    fun `brands are ranked by their soonest expiry, no expiry last`() {
        val groups = brandGroups(
            listOf(
                coupon(brand = "무기한"),
                coupon(brand = "늦게", expiresAt = "2026-12-31"),
                coupon(brand = "먼저", expiresAt = "2026-11-01"),
            ),
            NOW,
        )
        assertEquals(listOf("먼저", "늦게", "무기한"), groups.map { it.brand })
        assertEquals(listOf(0, 1, 2), groups.map { it.rank })
    }

    @Test
    fun `branch spots carry the brand's group, tier and rank`() {
        val g = BrandGroup(key = "starbucks", brand = "Starbucks", tier = Tier.URGENT, rank = 2)
        val spot = branchSpots(g, listOf(com.capstone.snapspot.core.model.Branch("42", "스타벅스 성수", "", 37.5, 127.0))).single()
        assertEquals("b|starbucks|42", spot.id)
        assertEquals("starbucks", spot.group)
        assertEquals(Tier.URGENT, spot.tier)
        assertEquals(2, spot.rank)
        assertEquals(BRANCH_RADIUS_M, spot.radius)
    }
}
