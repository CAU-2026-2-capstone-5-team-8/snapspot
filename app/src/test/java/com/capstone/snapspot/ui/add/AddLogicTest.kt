package com.capstone.snapspot.ui.add

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.FakeKakao
import com.capstone.snapspot.core.KakaoDoc
import com.capstone.snapspot.core.KakaoException
import com.capstone.snapspot.core.NOW
import com.capstone.snapspot.core.AddressDoc
import com.capstone.snapspot.core.KakaoApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AddLogicTest {
    private val geocoded = Coords(37.0, 127.0)
    private val geocoder: suspend (String, String) -> Coords? = { _, _ -> geocoded }

    @Test
    fun `a good Kakao match places the pin`() = runTest {
        val k = FakeKakao(
            mapOf("성동구 어니언 성수" to listOf(KakaoDoc(id = "1", place_name = "어니언 성수", category_group_code = "CE7", road_address_name = "서울 성동구 아차산로9길 8", x = "127.05", y = "37.54"))),
        )
        val r = locate("어니언 성수", "서울 성동구 아차산로9길 8", "카페", k, geocoder)
        assertEquals(Coords(37.54, 127.05), r.coords)
        assertEquals("1", r.matched?.kakaoId)
    }

    @Test
    fun `weak Kakao results fall back to the device geocoder but stay as choices`() = runTest {
        val k = FakeKakao(mapOf("어니언 성수" to listOf(KakaoDoc(id = "9", place_name = "김밥천국", category_group_code = "FD6", x = "1", y = "2"))))
        val r = locate("어니언 성수", "", "카페", k, geocoder)
        assertEquals(geocoded, r.coords)
        assertNull(r.matched)
        assertEquals(listOf("9"), r.candidates.map { it.kakaoId })
    }

    @Test
    fun `Kakao errors fall back to the device geocoder`() = runTest {
        val failing = object : KakaoApi {
            override suspend fun keyword(params: Map<String, String>): List<KakaoDoc> = throw KakaoException("카카오 검색 실패 (401): x")
            override suspend fun address(query: String) = emptyList<AddressDoc>()
        }
        assertEquals(geocoded, locate("어니언", "성수동", "카페", failing, geocoder).coords)
    }

    @Test
    fun `place built from a draft cleans up empty and malformed fields`() {
        val d = Draft(stage = Stage.READY, kind = "place", imageUri = "file:///x.jpg", name = "", category = "", endDate = "10월 31일", coords = Coords(1.0, 2.0), radius = 300)
        val p = buildPlace(d, "p_1", NOW)!!
        assertEquals("이름 없는 장소", p.name)
        assertEquals("기타", p.category)
        assertNull(p.endDate)
        assertEquals(300, p.radius)
        assertEquals(1.0, p.latitude)
    }

    @Test
    fun `no place without coordinates, no coupon without a brand`() {
        assertNull(buildPlace(Draft(stage = Stage.READY, kind = "place", imageUri = ""), "p", NOW))
        assertNull(buildCoupon(Draft(stage = Stage.READY, kind = "coupon", imageUri = "", brand = "  "), "c", NOW))
    }

    @Test
    fun `coupon built from a draft defaults the benefit and keeps a valid date`() {
        val c = buildCoupon(Draft(stage = Stage.READY, kind = "coupon", imageUri = "u", brand = " 스타벅스 ", benefit = "", endDate = "2026-12-31"), "c_1", NOW)!!
        assertEquals("스타벅스", c.brand)
        assertEquals("쿠폰", c.benefit)
        assertEquals("2026-12-31", c.expiresAt)
    }
}
