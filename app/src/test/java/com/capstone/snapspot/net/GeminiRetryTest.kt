package com.capstone.snapspot.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class GeminiRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var gemini: GeminiClient

    private val ok = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":"{\"kind\":\"place\",\"brand\":\"\",\"benefit\":\"\",\"isPlace\":true,\"name\":\"어니언 성수\",\"address\":\"\",\"category\":\"카페\",\"note\":\"\"}"}]}}]}""",
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        gemini = GeminiClient("k", "m", OkHttpClient(), server.url("/v1beta").toString(), timeoutMs = 500, retryDelayMs = 0)
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `a server error is tried once more`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(ok)
        assertEquals("어니언 성수", gemini.extractText("x").name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a slow answer times out and is tried once more`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("{}"))
        server.enqueue(ok)
        assertEquals("어니언 성수", gemini.extractText("x").name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `gives up after the second try`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertThrows<InterruptedIOException> { gemini.extractText("x") }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a bad request is not tried again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        val e = assertThrows<GeminiHttpException> { gemini.extractText("x") }
        assertEquals(400, e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `too many requests is tried once more`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(ok)
        assertEquals("어니언 성수", gemini.extractText("x").name)
    }
}
