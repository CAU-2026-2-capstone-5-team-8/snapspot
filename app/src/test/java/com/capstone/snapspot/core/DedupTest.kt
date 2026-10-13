package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DedupTest {
    private val onion = place(id = "onion", name = "어니언 성수", kakaoId = "123")
    private val here = Coords(onion.latitude, onion.longitude)

    /** ~`m` meters east of the saved place (about 88km per degree of longitude at 37.5°N). */
    private fun east(m: Double) = Coords(here.latitude, here.longitude + m / 88_000)

    @Test
    fun `same kakao id is the same place even if renamed`() {
        assertEquals(onion, findDuplicatePlace(listOf(onion), "전혀 다른 이름", "123", east(500.0)))
    }

    @Test
    fun `same normalized name within 50m is the same place`() {
        assertEquals(onion, findDuplicatePlace(listOf(onion), "어니언 성수점", null, east(30.0)))
    }

    @Test
    fun `same name 200m apart is a different place`() {
        assertNull(findDuplicatePlace(listOf(onion), "어니언 성수", null, east(200.0)))
    }

    @Test
    fun `different kakao ids are different places even when close`() {
        assertNull(findDuplicatePlace(listOf(onion), "어니언 성수", "999", east(10.0)))
    }

    @Test
    fun `coupon duplicate needs brand, benefit and expiry to match`() {
        val saved = coupon(id = "s", brand = "스타벅스", benefit = "아메리카노 Tall", expiresAt = "2026-12-31")
        assertEquals(saved, findDuplicateCoupon(listOf(saved), "스타 벅스", "아메리카노  Tall", "2026-12-31"))
        assertNull(findDuplicateCoupon(listOf(saved), "스타벅스", "아메리카노 Tall", "2027-01-31"))
        assertNull(findDuplicateCoupon(listOf(saved), "스타벅스", "카페라떼", "2026-12-31"))
    }

    @Test
    fun `a used coupon is not a duplicate`() {
        val used = coupon(benefit = "아메리카노", expiresAt = "2026-12-31", usedAt = 1)
        assertNull(findDuplicateCoupon(listOf(used), "스타벅스", "아메리카노", "2026-12-31"))
    }
}
