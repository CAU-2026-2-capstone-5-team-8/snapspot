package com.capstone.snapspot.net

import com.capstone.snapspot.core.Coords
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class TmapWalkTest {
    private lateinit var server: MockWebServer
    private lateinit var tmap: TmapWalk

    /** Trimmed from a real response: totals on the first Point, geometry on LineStrings that share their ends. */
    private val body = """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[127.0556,37.5445]},"properties":{"totalDistance":1558,"totalTime":1230,"pointType":"SP"}},
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.0556,37.5445],[127.0551,37.5447]]},"properties":{"distance":51}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[127.0551,37.5447]},"properties":{"turnType":12}},
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.0551,37.5447],[127.0530,37.5420],[127.0470,37.5470]]},"properties":{"distance":325}}
    ]}"""

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        tmap = TmapWalk("k", OkHttpClient(), server.url("/tmap/routes/pedestrian").toString())
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `sends the app key, start, end and waypoints as lon,lat`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        tmap.walk(listOf(Coords(37.5445, 127.0556), Coords(37.5420, 127.0530), Coords(37.5460, 127.0500), Coords(37.5470, 127.0470)))
        val req = server.takeRequest()
        assertEquals("k", req.getHeader("appKey"))
        val u = req.requestUrl!!
        assertEquals("127.0556", u.queryParameter("startX"))
        assertEquals("37.5445", u.queryParameter("startY"))
        assertEquals("127.047", u.queryParameter("endX"))
        assertEquals("37.547", u.queryParameter("endY"))
        assertEquals("127.053,37.542_127.05,37.546", u.queryParameter("passList"))
    }

    @Test
    fun `two points send no passList`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        tmap.walk(listOf(Coords(37.5445, 127.0556), Coords(37.5470, 127.0470)))
        assertNull(server.takeRequest().requestUrl!!.queryParameter("passList"))
    }

    @Test
    fun `parses totals and the street line without duplicate joints`() {
        val w = parseTmapWalk(body)
        assertEquals(1558, w.meters)
        assertEquals(1230, w.seconds)
        assertEquals(
            listOf(Coords(37.5445, 127.0556), Coords(37.5447, 127.0551), Coords(37.5420, 127.0530), Coords(37.5470, 127.0470)),
            w.line,
        )
    }

    @Test
    fun `an error response throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"INVALID_API_KEY"}}"""))
        assertThrows<IOException> { tmap.walk(listOf(Coords(37.5, 127.0), Coords(37.6, 127.1))) }
    }

    @Test
    fun `a body without a route throws`() {
        assertThrows<IOException> { parseTmapWalk("""{"error":{"id":"400","code":"1100"}}""") }
    }

    @Test
    fun `no key throws without calling`() = runTest {
        val none = TmapWalk("", OkHttpClient(), server.url("/").toString())
        assertThrows<IOException> { none.walk(listOf(Coords(37.5, 127.0), Coords(37.6, 127.1))) }
        assertEquals(0, server.requestCount)
    }
}
