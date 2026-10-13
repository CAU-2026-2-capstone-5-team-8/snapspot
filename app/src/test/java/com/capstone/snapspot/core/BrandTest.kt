package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrandTest {
    private fun d(name: String, category: String) =
        KakaoDoc(id = name, place_name = name, category_name = category, x = "127.05", y = "37.54")

    @Test
    fun `keeps real branches via the brand at the end of category_name (real 올리브영 results, 2026-10-10)`() {
        val docs = listOf(
            d("올리브영 뷰티 맨션 성수", "가정,생활 > 드럭스토어 > 올리브영"),
            d("하파크리스틴 올리브 영 성수 뷰티맨션점", "가정,생활 > 패션 > 패션잡화점 > 안경,렌즈 > 하파크리스틴"),
            d("투쿨포스쿨 올리브영N 성수 팝업", "서비스,산업 > 마케팅 > 상품전시,매장전시"),
            d("올리브영 N성수", "가정,생활 > 드럭스토어 > 올리브영"),
            d("올리브영 성수역점", "가정,생활 > 드럭스토어 > 올리브영"),
        )
        assertEquals(listOf("올리브영 뷰티 맨션 성수", "올리브영 N성수", "올리브영 성수역점"), pickBranches("올리브영", docs).map { it.place_name })
    }

    @Test
    fun `follows Kakao alias when the coupon name differs (메가커피 to 메가MGC커피)`() {
        val docs = listOf("성수역점", "뚝섬역점", "서울숲역점").map { d("메가MGC커피 $it", "음식점 > 카페 > 커피전문점 > 메가MGC커피") }
        assertEquals(3, pickBranches("메가커피", docs).size)
    }

    @Test
    fun `falls back to name prefix when Kakao has no brand category`() {
        val docs = listOf(
            d("동네빵집 성수점", "음식점 > 간식 > 제과,베이커리"),
            d("동네빵집 뚝섬점", "음식점 > 간식 > 제과,베이커리"),
            d("옆집베이커리", "음식점 > 간식 > 제과,베이커리"),
        )
        assertEquals(listOf("동네빵집 성수점", "동네빵집 뚝섬점"), pickBranches("동네빵집", docs).map { it.place_name })
    }

    @Test
    fun `does not let a generic category pass as a brand`() {
        val docs = listOf(d("카페A", "음식점 > 카페"), d("카페B", "음식점 > 카페"), d("스타벅스 성수점", "음식점 > 카페 > 커피전문점 > 스타벅스"))
        assertEquals(listOf("스타벅스 성수점"), pickBranches("스타벅스", docs).map { it.place_name })
    }

    @Test
    fun `brandKey is stable across spacing and case`() {
        assertEquals(brandKey("starbucks"), brandKey(" Starbucks "))
        assertEquals(brandKey("올리브영"), brandKey("올리브 영"))
    }
}
