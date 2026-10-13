package com.capstone.snapspot.net

import com.capstone.snapspot.core.KakaoException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KakaoHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var kakao: KakaoHttp

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        kakao = KakaoHttp("k", OkHttpClient(), server.url("/v2/local/search").toString())
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `sends the REST key and Korean query, parses documents`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"documents":[{"id":"1","place_name":"어니언 성수","category_name":"음식점 > 카페","category_group_code":"CE7",
                "address_name":"서울 성동구 성수동2가","road_address_name":"서울 성동구 아차산로9길 8","x":"127.05","y":"37.54",
                "place_url":"http://place.map.kakao.com/1","distance":""}],"meta":{"total_count":1}}""",
            ),
        )
        val docs = kakao.keyword(mapOf("query" to "어니언 성수", "size" to "10"))
        assertEquals("어니언 성수", docs.single().place_name)
        val req = server.takeRequest()
        assertEquals("KakaoAK k", req.getHeader("Authorization"))
        assertEquals("/v2/local/search/keyword.json", req.requestUrl!!.encodedPath)
        assertEquals("어니언 성수", req.requestUrl!!.queryParameter("query"))
    }

    @Test
    fun `address search parses road address`() = runTest {
        server.enqueue(MockResponse().setBody("""{"documents":[{"address_name":"서울 영등포구 여의도동 22","x":"126.9","y":"37.5","road_address":{"address_name":"서울 영등포구 여의대로 108"}}]}"""))
        val a = kakao.address("서울 영등포구 여의대로 108").single()
        assertEquals("서울 영등포구 여의대로 108", a.road_address!!.address_name)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("size"))
    }

    @Test
    fun `HTTP error becomes a Korean KakaoException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"errorType":"AccessDeniedError"}"""))
        val e = assertThrows<KakaoException> { kakao.keyword(mapOf("query" to "x")) }
        assertTrue(e.message!!.startsWith("카카오 검색 실패 (401):"), e.message)
    }

    @Test
    fun `region name is the administrative dong`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"documents":[{"region_type":"B","region_3depth_name":"성수동2가"},{"region_type":"H","region_3depth_name":"성수2가1동"}]}""",
            ),
        )
        assertEquals("성수2가1동", kakao.regionName(com.capstone.snapspot.core.Coords(37.5446, 127.056)))
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v2/local/geo/coord2regioncode.json"))
        assertTrue(req.path!!.contains("x=127.056") && req.path!!.contains("y=37.5446"))
    }
}
