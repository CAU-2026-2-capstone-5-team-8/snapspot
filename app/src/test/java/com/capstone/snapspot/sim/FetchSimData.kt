/**
 * One-off: pulls real Seoul places and franchise branches from Kakao Local into a test fixture, so
 * the simulation runs on where people actually save places instead of a uniform random city.
 *
 *   ./gradlew :app:testDebugUnitTest -PsimFetch   # needs KAKAO_REST_KEY in local.properties
 *
 * Writes app/src/test/resources/sim/seoul.json. Run it again only to refresh the data.
 */
package com.capstone.snapspot.sim

import com.capstone.snapspot.core.model.KakaoDoc
import com.capstone.snapspot.core.pickBranches
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

@Serializable
data class SimPlace(val name: String, val category: String, val hood: String, val lat: Double, val lng: Double)

@Serializable
data class SimBranch(val lat: Double, val lng: Double)

@Serializable
data class SimData(
    /** Where the data came from and when, for the report. */
    val source: String,
    val hoods: List<Hood>,
    val places: List<SimPlace>,
    /** Brand key → branches, in rank order (index 0 first). */
    val branches: Map<String, List<SimBranch>>,
)

@Serializable
data class Hood(val name: String, val lat: Double, val lng: Double)

/** Neighborhoods people screenshot cafés, restaurants and pop-ups in. */
val HOODS = listOf(
    Hood("성수", 37.5446, 127.0560),
    Hood("연남·홍대", 37.5600, 126.9240),
    Hood("한남·이태원", 37.5345, 126.9940),
    Hood("을지로", 37.5660, 126.9910),
    Hood("잠실", 37.5110, 127.0980),
    Hood("강남역", 37.4980, 127.0276),
    Hood("여의도", 37.5250, 126.9250),
    Hood("서촌·삼청", 37.5800, 126.9800),
)

/** Kakao category code → how many places to keep per neighborhood. */
private val PER_HOOD = listOf("CE7" to 10, "FD6" to 6, "CT1" to 4)

/** Query → brand key used in the simulation. */
val SIM_BRANDS = listOf("스타벅스" to "starbucks", "메가커피" to "megacoffee", "올리브영" to "oliveyoung")

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

class FetchSimData {
    private val key = System.getProperty("sim.kakaoKey").orEmpty()
    private val http = OkHttpClient()

    private fun get(path: String, params: Map<String, String>): List<KakaoDoc> {
        val url = "https://dapi.kakao.com/v2/local/search/$path.json".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "Kakao ${res.code} for $url" }
            val body = json.parseToJsonElement(res.body!!.string())
            val docs = body.jsonObjectField("documents")
            return json.decodeFromJsonElement(ListSerializer(KakaoDoc.serializer()), docs)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectField(name: String) =
        (this as kotlinx.serialization.json.JsonObject).getValue(name)

    private fun places(): List<SimPlace> = HOODS.flatMap { h ->
        PER_HOOD.flatMap { (code, keep) ->
            val docs = (1..2).flatMap { page ->
                get("category", mapOf("category_group_code" to code, "x" to "${h.lng}", "y" to "${h.lat}", "radius" to "800", "sort" to "accuracy", "page" to "$page", "size" to "15"))
            }
            docs.distinctBy { it.id }.take(keep).map { SimPlace(it.place_name, code, h.name, it.y.toDouble(), it.x.toDouble()) }
        }
    }

    /** Seoul on a ~1.4km grid, 1km search radius per cell (the cells overlap), up to 45 hits per query. */
    private fun branches(query: String): List<SimBranch> {
        val seen = LinkedHashMap<String, SimBranch>()
        var lat = 37.43
        while (lat <= 37.70) {
            var lng = 126.80
            while (lng <= 127.19) {
                for (page in 1..3) {
                    val docs = get("keyword", mapOf("query" to query, "x" to "$lng", "y" to "$lat", "radius" to "1000", "page" to "$page", "size" to "15"))
                    pickBranches(query, docs).forEach { seen.getOrPut(it.id) { SimBranch(it.y.toDouble(), it.x.toDouble()) } }
                    if (docs.size < 15) break
                }
                lng += 0.0159
            }
            lat += 0.0126
        }
        return seen.values.toList()
    }

    @Test
    fun fetch() {
        assumeTrue(System.getProperty("sim.fetch") == "true", "run with ./gradlew :app:testDebugUnitTest -PsimFetch")
        check(key.isNotEmpty()) { "KAKAO_REST_KEY missing from local.properties" }
        val places = places()
        println("places: ${places.size}")
        val branches = SIM_BRANDS.associate { (query, k) -> k to branches(query).also { println("$k: ${it.size}") } }
        val data = SimData("카카오 로컬 API, ${java.time.LocalDate.now()}", HOODS, places, branches)
        val out = File(requireNotNull(System.getProperty("sim.dataOut")))
        out.parentFile?.mkdirs()
        out.writeText(json.encodeToString(SimData.serializer(), data))
        println("wrote $out")
    }
}
