package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Place
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

fun place(
    id: String = "p",
    name: String = "어니언 성수",
    category: String = "카페",
    note: String = "",
    endDate: String? = null,
    latitude: Double = 37.5447,
    longitude: Double = 127.0582,
    kakaoId: String? = null,
    lastNotifiedAt: Long? = null,
) = Place(
    id = id, name = name, address = "서울 성동구 아차산로9길 8", category = category, note = note, endDate = endDate,
    latitude = latitude, longitude = longitude, radius = 200, imageUri = null, kakaoId = kakaoId,
    createdAt = 0, lastNotifiedAt = lastNotifiedAt,
)

class AlertsTest {
    @Test
    fun `place past its end date is suppressed as ended`() {
        assertEquals("운영 종료", suppressReason(place(endDate = "2026-10-09"), NOW))
        assertTrue(placeEnded(place(endDate = "2026-10-09"), NOW))
        assertFalse(placeEnded(place(endDate = "2026-10-10"), NOW))
    }

    @Test
    fun `place alerted within 6 hours is suppressed, later it alerts again`() {
        assertEquals("최근에 알림", suppressReason(place(lastNotifiedAt = NOW - 1000), NOW))
        assertNull(suppressReason(place(lastNotifiedAt = NOW - PLACE_COOLDOWN_MS), NOW))
        assertNull(suppressReason(place(), NOW))
    }

    @Test
    fun `place alert text with and without a note`() {
        assertEquals("📍 저장해 둔 카페 근처예요" to "어니언 성수 · 소금빵 맛집", placeAlertText(place(note = "소금빵 맛집")))
        assertEquals("어니언 성수", placeAlertText(place()).second)
    }

    @Test
    fun `coupon alert shows D-day for urgent coupons`() {
        val (title, body) = couponAlertText("스타벅스 뚝섬역", listOf(coupon(expiresAt = "2026-10-12")), NOW)
        assertEquals("🎟️ 스타벅스 뚝섬역 근처예요", title)
        assertEquals("스타벅스 아메리카노 · 10월 12일까지 · D-2", body)
    }

    @Test
    fun `coupon alert without urgency counts the extra coupons`() {
        val list = listOf(coupon(expiresAt = "2026-12-31"), coupon(id = "b", benefit = "케이크"))
        assertEquals("스타벅스 아메리카노 · 12월 31일까지 외 1장", couponAlertText("스타벅스 성수", list, NOW).second)
    }
}
