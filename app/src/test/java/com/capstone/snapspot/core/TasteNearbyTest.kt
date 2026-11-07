package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Place
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TasteNearbyTest {
    private val here = Coords(37.5446, 127.0560)

    private fun place(name: String, category: String, kakaoId: String? = null, note: String = "") = Place(
        id = name, name = name, address = "서울 성동구", category = category, note = note, endDate = null,
        latitude = 37.55, longitude = 127.05, radius = 200, imageUri = null, kakaoId = kakaoId, createdAt = 0,
    )

    private fun doc(id: String, name: String, category: String, dy: Double = 0.001) = KakaoDoc(
        id = id, place_name = name, category_name = category, x = (here.longitude).toString(), y = (here.latitude + dy).toString(),
        place_url = "http://place.map.kakao.com/$id",
    )

    /** Kakao answers per category group code and per keyword; records the parameters. */
    private class CategoryKakao(private val byCode: Map<String, List<KakaoDoc>>, private val byQuery: Map<String, List<KakaoDoc>> = emptyMap()) : KakaoApi {
        val asked = mutableListOf<Map<String, String>>()
        val queries = mutableListOf<Map<String, String>>()
        override suspend fun keyword(params: Map<String, String>): List<KakaoDoc> {
            queries += params
            return byQuery[params.getValue("query")].orEmpty()
        }
        override suspend fun address(query: String) = emptyList<AddressDoc>()
        override suspend fun category(params: Map<String, String>): List<KakaoDoc> {
            asked += params
            return byCode[params.getValue("category_group_code")].orEmpty()
        }
    }

    /** Dessert texts point one way, everything else another, so the dessert candidate stands out. */
    private class WordEmbedder : Embedder {
        val sent = mutableListOf<String>()
        override suspend fun embed(texts: List<String>): List<FloatArray> {
            sent += texts
            return texts.map { t ->
                when {
                    "디저트" in t || "베이커리" in t -> floatArrayOf(1f, 0f, 0f)
                    "술집" in t -> floatArrayOf(0f, 1f, 0f)
                    else -> floatArrayOf(0f, 0f, 1f)
                }
            }
        }
    }

    private val saved = listOf(
        place("버터컵", "디저트카페", note = "친구랑 꼭"),
        place("어니언", "베이커리"),
        place("누데이크", "디저트카페"),
    )

    private val kakao = CategoryKakao(
        mapOf(
            "CE7" to listOf(
                doc("1", "달콤디저트", "음식점 > 카페 > 디저트카페"),
                doc("2", "동네커피", "음식점 > 카페"),
                doc("3", "역앞커피", "음식점 > 카페"),
            ),
            "FD6" to listOf(
                doc("4", "성수술집", "음식점 > 술집"),
                doc("5", "김밥천국", "음식점 > 분식"),
                doc("6", "국밥집", "음식점 > 한식"),
            ),
            "CT1" to listOf(doc("7", "작은극장", "문화,예술 > 공연장")),
            "AT4" to listOf(doc("8", "서울숲", "여행 > 공원")),
        ),
    )

    @Test
    fun `the candidate like the saved places comes first, with its short category and distance`() = runTest {
        val out = suggestNearby(here, saved, kakao, WordEmbedder())
        assertEquals("달콤디저트", out.first().name)
        assertEquals("디저트카페", out.first().category)
        assertEquals("1", out.first().kakaoId)
        assertTrue(out.first().meters in 100.0..120.0, "${out.first().meters}")
        assertTrue(out.size <= 3)
    }

    @Test
    fun `asks each category group around the user within 1km`() = runTest {
        suggestNearby(here, saved, kakao, WordEmbedder())
        assertEquals(TASTE_CATEGORIES, kakao.asked.map { it["category_group_code"] })
        assertTrue(kakao.asked.all { it["radius"] == "1000" && it["x"] == here.longitude.toString() && it["y"] == here.latitude.toString() })
    }

    @Test
    fun `searches the most saved categories around the user, most frequent first`() = runTest {
        suggestNearby(here, saved, kakao, WordEmbedder())
        assertEquals(listOf("디저트카페", "베이커리"), kakao.queries.map { it["query"] })
        assertTrue(kakao.queries.all { it["radius"] == "1000" && it["x"] == here.longitude.toString() && it["y"] == here.latitude.toString() })
    }

    @Test
    fun `at most three kinds are searched, and 기타 is not one`() {
        val many = listOf("술집", "술집", "디저트", "전시", "공원", "기타", "기타", "기타").mapIndexed { i, c -> place("p$i", c) }
        assertEquals(listOf("술집", "디저트", "전시"), tasteQueries(many))
    }

    @Test
    fun `a place only the keyword search finds can be suggested`() = runTest {
        val k = CategoryKakao(
            mapOf("FD6" to listOf(doc("4", "성수술집", "음식점 > 술집"), doc("5", "김밥천국", "음식점 > 분식"), doc("6", "국밥집", "음식점 > 한식"), doc("7", "짜장면집", "음식점 > 중식"))),
            mapOf("디저트카페" to listOf(doc("1", "달콤디저트", "음식점 > 카페 > 디저트카페"))),
        )
        assertEquals("1", suggestNearby(here, saved, k, WordEmbedder()).first().kakaoId)
        assertTrue(suggestNearby(here, saved, CategoryKakao(emptyMap(), mapOf("디저트카페" to listOf(doc("1", "달콤디저트", "디저트카페")))), WordEmbedder(), byTaste = false).isEmpty())
    }

    @Test
    fun `only names and categories are embedded, never memos or addresses`() = runTest {
        val e = WordEmbedder()
        suggestNearby(here, saved, kakao, e)
        assertTrue(e.sent.none { "친구랑" in it || "성동구" in it }, e.sent.toString())
        assertTrue("버터컵 | 디저트카페" in e.sent)
    }

    @Test
    fun `already saved places are not suggested`() = runTest {
        val out = suggestNearby(here, saved + place("달콤디저트", "디저트카페", kakaoId = "1"), kakao, WordEmbedder())
        assertNull(out.firstOrNull { it.kakaoId == "1" })
    }

    @Test
    fun `fewer than three saves means no suggestions and no requests`() = runTest {
        val e = WordEmbedder()
        assertEquals(emptyList<Suggestion>(), suggestNearby(here, saved.take(2), kakao, e))
        assertTrue(kakao.asked.isEmpty() && e.sent.isEmpty())
    }

    @Test
    fun `franchise branches are left out, places whose name only shares a word are not`() {
        assertTrue(isFranchise(doc("9", "메가MGC커피 성수역점", "음식점 > 카페 > 커피전문점 > 메가MGC커피")))
        assertTrue(isFranchise(doc("9", "스타벅스 성수점", "음식점 > 카페 > 커피전문점 > 스타벅스")))
        assertTrue(!isFranchise(doc("9", "북눅 성수", "음식점 > 카페 > 북카페")))
        assertTrue(!isFranchise(doc("9", "카페 어니언", "음식점 > 카페")))
    }

    @Test
    fun `a franchise branch is never suggested`() = runTest {
        val k = CategoryKakao(mapOf("CE7" to listOf(doc("1", "달콤디저트 성수점", "음식점 > 카페 > 디저트카페 > 달콤디저트"), doc("2", "동네커피", "음식점 > 카페"), doc("3", "디저트하우스", "음식점 > 카페 > 디저트카페"))))
        val out = suggestNearby(here, saved, k, WordEmbedder())
        assertNull(out.firstOrNull { it.kakaoId == "1" })
        assertEquals("3", out.first().kakaoId)
    }

    @Test
    fun `a suggestion saves as a place with Kakao id and link, no screenshot`() {
        val s = Suggestion("1", "달콤디저트", "디저트카페", "서울 성동구 1", 37.5, 127.0, "http://x", 100.0)
        val p = placeFromSuggestion(s, "p_1", 42)
        assertEquals("1", p.kakaoId)
        assertEquals("http://x", p.placeUrl)
        assertNull(p.imageUri)
        assertEquals(200, p.radius)
        assertEquals("디저트카페", p.category)
    }
}
