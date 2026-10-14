package com.capstone.snapspot.net

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.WALK_MAX_POINTS
import com.capstone.snapspot.core.WalkApi
import com.capstone.snapspot.core.WalkPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** TMAP pedestrian routes over HTTP with the app key. */
class TmapWalk(
    private val key: String,
    private val client: OkHttpClient,
    private val base: String = "https://apis.openapi.sk.com/tmap/routes/pedestrian",
) : WalkApi {

    override suspend fun walk(points: List<Coords>): WalkPath {
        require(points.size in 2..WALK_MAX_POINTS)
        if (key.isEmpty()) throw IOException("TMAP 앱 키가 없어요. local.properties의 TMAP_APP_KEY를 설정하세요.")
        fun x(c: Coords) = c.longitude.toString()
        fun y(c: Coords) = c.latitude.toString()
        val url = base.toHttpUrl().newBuilder().apply {
            addQueryParameter("version", "1")
            addQueryParameter("startX", x(points.first()))
            addQueryParameter("startY", y(points.first()))
            addQueryParameter("endX", x(points.last()))
            addQueryParameter("endY", y(points.last()))
            val pass = points.subList(1, points.size - 1)
            if (pass.isNotEmpty()) addQueryParameter("passList", pass.joinToString("_") { "${x(it)},${y(it)}" })
            addQueryParameter("startName", "출발")
            addQueryParameter("endName", "도착")
        }.build()
        val req = Request.Builder().url(url).header("appKey", key).build()
        client.newCall(req).await().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("TMAP 도보 경로 실패 (${res.code}): ${body.take(200)}")
            return parseTmapWalk(body)
        }
    }
}

/** GeoJSON: the first feature carries the totals, LineStrings carry the street geometry ([lon, lat]). */
internal fun parseTmapWalk(body: String): WalkPath {
    val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
        ?: throw IOException("TMAP 도보 경로 응답에 경로가 없어요: ${body.take(200)}")
    val totals = features.firstOrNull()?.jsonObject?.get("properties")?.jsonObject
    val line = mutableListOf<Coords>()
    for (f in features) {
        val geometry = f.jsonObject["geometry"] as? JsonObject ?: continue
        if (geometry["type"]?.jsonPrimitive?.content != "LineString") continue
        for (p in geometry["coordinates"] as? JsonArray ?: continue) {
            val c = Coords(p.jsonArray[1].jsonPrimitive.double, p.jsonArray[0].jsonPrimitive.double)
            if (line.lastOrNull() != c) line += c
        }
    }
    val meters = totals?.get("totalDistance")?.jsonPrimitive?.int
    val seconds = totals?.get("totalTime")?.jsonPrimitive?.int
    if (meters == null || seconds == null || line.size < 2) throw IOException("TMAP 도보 경로 응답을 읽지 못했어요: ${body.take(200)}")
    return WalkPath(line, meters, seconds)
}
