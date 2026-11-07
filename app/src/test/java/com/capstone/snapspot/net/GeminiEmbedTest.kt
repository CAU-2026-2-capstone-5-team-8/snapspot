package com.capstone.snapspot.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeminiEmbedTest {
    private lateinit var server: MockWebServer
    private lateinit var gemini: GeminiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        gemini = GeminiClient("gk", "m1", OkHttpClient(), server.url("/v1beta").toString(), retryDelayMs = 0, embedModel = "e1")
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `embeds many texts in one batch request, in order`() = runTest {
        server.enqueue(MockResponse().setBody("""{"embeddings":[{"values":[1,0]},{"values":[0.5,-0.25]}]}"""))
        val out = gemini.embed(listOf("어니언 성수 | 카페", "옥동식 | 국밥"))
        assertArrayEquals(floatArrayOf(1f, 0f), out[0])
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), out[1])
        val req = server.takeRequest()
        assertEquals("/v1beta/models/e1:batchEmbedContents", req.requestUrl!!.encodedPath)
        assertEquals("gk", req.getHeader("x-goog-api-key"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"model\":\"models/e1\""), body)
        assertTrue(body.contains("옥동식 | 국밥"), body)
    }

    @Test
    fun `nothing to embed makes no request`() = runTest {
        assertEquals(0, gemini.embed(emptyList()).size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `long lists are split into batches of 100`() = runTest {
        fun reply(n: Int) = MockResponse().setBody("""{"embeddings":[${List(n) { """{"values":[1]}""" }.joinToString(",")}]}""")
        server.enqueue(reply(100))
        server.enqueue(reply(30))
        assertEquals(130, gemini.embed(List(130) { "t$it" }).size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a missing or short answer is an error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"embeddings":[{"values":[1]}]}"""))
        assertThrows<IllegalStateException> { gemini.embed(listOf("a", "b")) }
    }

    @Test
    fun `a server error is tried once more`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"embeddings":[{"values":[1,2]}]}"""))
        assertArrayEquals(floatArrayOf(1f, 2f), gemini.embed(listOf("a"))[0])
    }

    @Test
    fun `missing key explains where to set it`() = runTest {
        val g = GeminiClient("", "m1", OkHttpClient())
        val e = assertThrows<IllegalStateException> { g.embed(listOf("a")) }
        assertTrue(e.message!!.contains("local.properties의 GEMINI_API_KEY"), e.message)
    }
}
