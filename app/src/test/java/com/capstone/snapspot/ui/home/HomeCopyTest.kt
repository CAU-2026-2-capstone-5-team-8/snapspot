package com.capstone.snapspot.ui.home

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.NOW
import com.capstone.snapspot.core.coupon
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.place
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeCopyTest {
    @Test
    fun `copula follows the last syllable`() {
        assertEquals("성수동이에요", copula("성수동"))
        assertEquals("을지로3가예요", copula("을지로3가"))
        assertEquals("성수예요", copula("성수"))
        assertEquals("명동1이에요", copula("명동1"))
        assertEquals("강남2예요", copula("강남2"))
        assertEquals("", copula(""))
    }

    @Test
    fun `places nearby are counted and the count is highlighted`() {
        val h = homeHeadline("성수동", near = 2, saved = 5, alertsOn = true)
        assertEquals("지금 성수동이에요", h.kicker)
        assertEquals("2곳", h.count)
        assertEquals("근처에 가 볼 곳이 2곳 있어요", h.text)
    }

    @Test
    fun `nothing nearby just counts the saved places`() {
        val h = homeHeadline(null, near = 0, saved = 5, alertsOn = true)
        assertNull(h.kicker)
        assertNull(h.count)
        assertEquals("저장한 곳 5곳", h.text)
    }

    @Test
    fun `alerts off is said in the headline`() {
        assertEquals("저장한 곳 5곳, 알림은 꺼져 있어요", homeHeadline(null, near = 0, saved = 5, alertsOn = false).text)
    }

    @Test
    fun `no saved places invites the first screenshot`() {
        assertEquals("가 보고 싶은 곳을 저장해 보세요", homeHeadline("성수동", near = 0, saved = 0, alertsOn = true).text)
    }

    @Test
    fun `near means inside the radius of a place that has not ended`() {
        val inside = place(id = "in")
        val far = place(id = "far", latitude = 37.5547)
        val ended = place(id = "ended", endDate = "2026-10-09")
        val here = Coords(37.5447, 127.0582)
        assertEquals(setOf("in"), nearPlaces(listOf(inside, far, ended), here, NOW))
        assertEquals(emptySet<String>(), nearPlaces(listOf(inside), null, NOW))
    }

    @Test
    fun `place meta joins category, end date and note`() {
        assertEquals("카페 · 10월 31일까지 · 창가 자리", placeMeta(place(endDate = "2026-10-31", note = "창가 자리"), ended = false))
        assertEquals("팝업 · 운영 종료", placeMeta(place(category = "팝업", endDate = "2026-10-09"), ended = true))
        assertEquals("카페", placeMeta(place(), ended = false))
    }

    @Test
    fun `coupon meta prefers the nearest branch`() {
        val c = coupon(benefit = "아메리카노 1+1")
        assertEquals("아메리카노 1+1 · 성수역점 ${formatDistance(320.0)}", couponMeta(CouponItem(c, active = true, urgent = false, nearest = "성수역점" to 320.0)))
        assertEquals("사용한 쿠폰이에요", couponMeta(CouponItem(coupon(usedAt = 1L), active = false, urgent = false, nearest = null)))
        assertEquals("기간이 지났어요", couponMeta(CouponItem(c, active = false, urgent = false, nearest = null)))
        assertEquals("아메리카노 1+1 · 12월 31일까지", couponMeta(CouponItem(coupon(benefit = "아메리카노 1+1", expiresAt = "2026-12-31"), active = true, urgent = false, nearest = null)))
    }

    @Test
    fun `bundle titles read as a course unless already a near-name`() {
        assertEquals("성수동 코스", bundleTitle("성수동"))
        assertEquals("어니언 성수 근처", bundleTitle("어니언 성수 근처"))
    }

    @Test
    fun `list header counts the rows above the first place`() {
        assertEquals(2, listHeader(alertsOn = true, bundles = false))
        assertEquals(3, listHeader(alertsOn = true, bundles = true))
        assertEquals(4, listHeader(alertsOn = false, bundles = true))
    }
}
