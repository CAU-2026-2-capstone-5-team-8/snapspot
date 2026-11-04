package com.capstone.snapspot.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeminiParseTest {
    @Test
    fun `parses a place and trims fields`() {
        val e = parseExtracted(
            """{"kind":"place","brand":"","benefit":"","isPlace":true,"name":" 어니언 성수 ","address":"성수동","category":"카페","note":"소금빵 맛집","endDate":"2026-10-31"}""",
        )
        assertEquals("place", e.kind)
        assertEquals("어니언 성수", e.name)
        assertEquals("2026-10-31", e.endDate)
        assertTrue(e.isPlace)
    }

    @Test
    fun `bad date becomes null, unknown kind becomes place, empty category becomes 기타`() {
        val e = parseExtracted("""{"kind":"gift","isPlace":false,"name":"x","category":"  ","endDate":"10월 31일"}""")
        assertEquals("place", e.kind)
        assertNull(e.endDate)
        assertEquals("기타", e.category)
        assertFalse(e.isPlace)
        assertEquals("", e.brand)
    }

    @Test
    fun `coupon kind is kept`() {
        assertEquals("coupon", parseExtracted("""{"kind":"coupon","brand":"스타벅스","benefit":"아메리카노"}""").kind)
    }

    @Test
    fun `sends the image and reads the first candidate text`() = runTest {
        val server = MockWebServer().apply { start() }
        val text = """{\"kind\":\"coupon\",\"brand\":\"스타벅스\",\"benefit\":\"아메리카노\",\"isPlace\":false,\"name\":\"\",\"address\":\"\",\"category\":\"카페\",\"note\":\"\",\"endDate\":null}"""
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""))
        val g = GeminiClient("gk", "m1", OkHttpClient(), server.url("/v1beta").toString())
        val e = g.extract("QUJD")
        assertEquals("스타벅스", e.brand)
        val req = server.takeRequest()
        assertEquals("/v1beta/models/m1:generateContent", req.requestUrl!!.encodedPath)
        assertEquals("gk", req.getHeader("x-goog-api-key"))
        assertTrue(req.body.readUtf8().contains("\"data\":\"QUJD\""))
        server.shutdown()
    }

    @Test
    fun `text-only reading sends the redacted text and no image`() = runTest {
        val server = MockWebServer().apply { start() }
        val text = """{\"kind\":\"place\",\"brand\":\"\",\"benefit\":\"\",\"isPlace\":true,\"name\":\"어니언 성수\",\"address\":\"성수동\",\"category\":\"카페\",\"note\":\"\",\"endDate\":null}"""
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""))
        val g = GeminiClient("gk", "m1", OkHttpClient(), server.url("/v1beta").toString())
        val e = g.extractText("어니언 성수\n문의 [전화번호]")
        assertEquals("어니언 성수", e.name)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("inline_data"))
        assertTrue(body.contains("[전화번호]"))
        server.shutdown()
    }

    @Test
    fun `empty candidates is an error with a Korean message`() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        val g = GeminiClient("gk", "m1", OkHttpClient(), server.url("/v1beta").toString())
        val e = assertThrows<IllegalStateException> { g.extract("QUJD") }
        assertEquals("Gemini 응답이 비어 있어요.", e.message)
        server.shutdown()
    }

    @Test
    fun `missing key explains where to set it`() = runTest {
        val g = GeminiClient("", "m1", OkHttpClient())
        val e = assertThrows<IllegalStateException> { g.extract("QUJD") }
        assertTrue(e.message!!.contains("local.properties의 GEMINI_API_KEY"), e.message)
    }
}
