package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchTest {
    private fun doc(
        placeName: String = "어니언 성수",
        code: String = "CE7",
        road: String = "서울 성동구 아차산로9길 8",
        lot: String = "서울 성동구 성수동2가 277-135",
    ) = KakaoDoc(
        id = "1", place_name = placeName, category_name = "음식점 > 카페", category_group_code = code,
        address_name = lot, road_address_name = road, x = "127.0582", y = "37.5447",
        place_url = "http://place.map.kakao.com/1",
    )

    @Test
    fun `normalizeName drops spaces, punctuation and a trailing 점`() {
        assertEquals("어니언성수", normalizeName("어니언 성수점"))
        assertEquals("cafeonionseongsu", normalizeName("Cafe ONION (Seongsu)"))
    }

    @Test
    fun `regionHint keeps 구 and 동 tokens only`() {
        assertEquals("성동구", regionHint("서울 성동구 아차산로9길 8"))
        assertEquals("성수동", regionHint("성수동"))
        assertEquals("", regionHint(""))
    }

    @Test
    fun `nameVariants drops trailing words, longest first`() {
        assertEquals(listOf("더현대 서울 B1 이벤트홀", "더현대 서울 B1", "더현대 서울", "더현대"), nameVariants("더현대 서울 B1 이벤트홀"))
        assertEquals(listOf("어니언"), nameVariants("어니언"))
        assertEquals(emptyList<String>(), nameVariants("  "))
    }

    @Test
    fun `exact name + address + category scores near 1`() {
        val s = scoreCandidate(doc(), Extract("어니언 성수", "서울 성동구 아차산로9길 8", "카페"))
        assertTrue(s > 0.95, "got $s")
    }

    @Test
    fun `same brand, other branch loses to the right branch via address`() {
        val ex = Extract("어니언", "서울 성동구 아차산로9길 8", "카페")
        val right = scoreCandidate(doc(), ex)
        val wrong = scoreCandidate(doc(placeName = "어니언 안국", road = "서울 종로구 계동길 5", lot = "서울 종로구 계동 146-1"), ex)
        assertTrue(right > wrong + 0.2, "right $right wrong $wrong")
    }

    @Test
    fun `venue name contained in a longer extracted name still matches`() {
        val s = scoreCandidate(
            doc(placeName = "더현대 서울", code = "", road = "서울 영등포구 여의대로 108"),
            Extract("더현대 서울 B1 이벤트홀", "서울 영등포구 여의대로 108", "팝업"),
        )
        assertTrue(s > 0.8, "got $s")
    }

    @Test
    fun `unrelated place scores low`() {
        val s = scoreCandidate(
            doc(placeName = "김밥천국 성수점", code = "FD6", road = "서울 성동구 왕십리로 1"),
            Extract("어니언 성수", "서울 성동구 아차산로9길 8", "카페"),
        )
        assertTrue(s < 0.4, "got $s")
    }

    @Test
    fun `missing address or category does not penalize a name match`() {
        val s = scoreCandidate(doc(), Extract("어니언 성수", "", "기타"))
        assertTrue(s > 0.9, "got $s")
    }
}
