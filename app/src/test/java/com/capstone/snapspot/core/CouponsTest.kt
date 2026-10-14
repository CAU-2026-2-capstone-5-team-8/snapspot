package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Coupon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

val NOW: Long = LocalDateTime.of(2026, 10, 10, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun coupon(
    id: String = "c",
    brand: String = "스타벅스",
    benefit: String = "아메리카노",
    expiresAt: String? = null,
    usedAt: Long? = null,
    lastNotifiedAt: Long? = null,
) = Coupon(id, brand, benefit, expiresAt, imageUri = null, createdAt = 0, usedAt = usedAt, lastNotifiedAt = lastNotifiedAt)

class CouponsTest {
    @Test
    fun `a coupon stays active through its last day, then expires`() {
        assertTrue(isActive(coupon(expiresAt = "2026-10-10"), NOW))
        assertFalse(isActive(coupon(expiresAt = "2026-10-09"), NOW))
        assertFalse(isActive(coupon(usedAt = 1), NOW))
        assertTrue(isActive(coupon(), NOW))
    }

    @Test
    fun `daysLeft counts whole days, 0 on the last day`() {
        assertEquals(0, daysLeft(coupon(expiresAt = "2026-10-10"), NOW))
        assertEquals(21, daysLeft(coupon(expiresAt = "2026-10-31"), NOW))
        assertNull(daysLeft(coupon(), NOW))
    }

    @Test
    fun `couponsForBrand matches across spacing and puts the soonest expiry first`() {
        val list = listOf(
            coupon(id = "late", brand = "스타 벅스", expiresAt = "2026-12-01"),
            coupon(id = "soon", expiresAt = "2026-10-20"),
            coupon(id = "other", brand = "올리브영"),
            coupon(id = "used", usedAt = 1),
        )
        assertEquals(listOf("soon", "late"), couponsForBrand(list, "스타벅스", NOW).map { it.id })
    }

    @Test
    fun `one alert for a brand cools down all its coupons`() {
        val list = listOf(coupon(id = "a", lastNotifiedAt = NOW - 1000), coupon(id = "b"))
        assertTrue(brandCoolingDown(list, "스타벅스", NOW))
        assertFalse(brandCoolingDown(list, "스타벅스", NOW + BRAND_COOLDOWN_MS))
        assertFalse(brandCoolingDown(list, "올리브영", NOW))
    }

    @Test
    fun `urgent means active and expiring within 3 days`() {
        assertTrue(isUrgent(coupon(expiresAt = "2026-10-13"), NOW))
        assertFalse(isUrgent(coupon(expiresAt = "2026-10-14"), NOW))
        assertFalse(isUrgent(coupon(expiresAt = "2026-10-11", usedAt = 1), NOW))
        assertFalse(isUrgent(coupon(), NOW))
    }

    @Test
    fun `dDay labels`() {
        assertNull(dDay(null))
        assertEquals("기간 지남", dDay(-1))
        assertEquals("오늘까지", dDay(0))
        assertEquals("D-5", dDay(5))
    }

    @Test
    fun `only real calendar dates count as dates`() {
        assertTrue(isDate("2026-12-31"))
        assertTrue(isDate("2028-02-29"))
        assertFalse(isDate("2026-02-30"))
        assertFalse(isDate("2026-13-01"))
        assertFalse(isDate("2026-1-5"))
        assertFalse(isDate(null))
    }
    @Test
    fun `swipeGroup is the brand's active coupons, soonest first, with the open one in it`() {
        val list = listOf(
            coupon(id = "late", expiresAt = "2026-12-01"),
            coupon(id = "soon", expiresAt = "2026-10-20"),
            coupon(id = "other", brand = "올리브영"),
            coupon(id = "used", usedAt = 1),
        )
        assertEquals(listOf("soon", "late"), swipeGroup(list, list[0], NOW).map { it.id })
    }

    @Test
    fun `swipeGroup leaves a used, expired or only coupon alone`() {
        val used = coupon(id = "used", usedAt = 1)
        val gone = coupon(id = "gone", expiresAt = "2026-10-01")
        val only = coupon(id = "only", brand = "올리브영")
        val list = listOf(used, gone, only, coupon(id = "a"), coupon(id = "b"))
        assertEquals(listOf("used"), swipeGroup(list, used, NOW).map { it.id })
        assertEquals(listOf("gone"), swipeGroup(list, gone, NOW).map { it.id })
        assertEquals(listOf("only"), swipeGroup(list, only, NOW).map { it.id })
    }

    @Test
    fun `swipeGroup keeps the open coupon when the list has not caught up with it`() {
        val fresh = coupon(id = "fresh")
        assertEquals(listOf("fresh"), swipeGroup(listOf(coupon(id = "a")), fresh, NOW).map { it.id })
    }

    @Test
    fun `placeDaysLeft counts to the end date, 0 on the last day, null once it passed or without one`() {
        assertEquals(21, placeDaysLeft(place(endDate = "2026-10-31"), NOW))
        assertEquals(0, placeDaysLeft(place(endDate = "2026-10-10"), NOW))
        assertNull(placeDaysLeft(place(endDate = "2026-10-09"), NOW))
        assertNull(placeDaysLeft(place(), NOW))
    }
}
