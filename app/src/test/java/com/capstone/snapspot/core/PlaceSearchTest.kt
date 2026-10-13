package com.capstone.snapspot.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceSearchTest {
    private fun doc(id: String, name: String, road: String, code: String = "") = KakaoDoc(
        id = id, place_name = name, category_group_code = code, address_name = road, road_address_name = road,
        x = "126.9", y = "37.5", place_url = "http://place.map.kakao.com/$id",
    )

    @Test
    fun `keeps shortening past noisy hits until a confident match (더현대 서울 B1 regression)`() = runTest {
        val k = FakeKakao(
            mapOf(
                // Real Kakao behavior on 2026-10-10: "B1" matches unrelated places.
                "더현대 서울 B1" to listOf(doc("9", "페어몬트앰배서더서울웨딩", "서울 영등포구 여의대로 108"), doc("8", "B1 홍대", "서울 마포구 와우산로 1")),
                "영등포구 더현대 서울" to listOf(doc("1", "더현대 서울", "서울 영등포구 여의대로 108")),
            ),
        )
        val out = searchPlace(Extract("더현대 서울 B1 이벤트홀", "서울 영등포구 여의대로 108", "팝업"), k)
        assertEquals("더현대 서울", out[0].name)
        assertTrue(out[0].score >= CONFIDENT, "score ${out[0].score}")
        assertFalse("kw:더현대" in k.asked, "should stop once confident")
    }

    @Test
    fun `stops at the full name when it already matches`() = runTest {
        val k = FakeKakao(mapOf("성동구 어니언 성수" to listOf(doc("1", "어니언 성수", "서울 성동구 아차산로9길 8", "CE7"))))
        val out = searchPlace(Extract("어니언 성수", "서울 성동구 아차산로9길 8", "카페"), k)
        assertEquals("어니언 성수", out[0].name)
        assertEquals(listOf("kw:성동구 어니언 성수", "kw:어니언 성수"), k.asked.filter { it.startsWith("kw:") })
    }
}
