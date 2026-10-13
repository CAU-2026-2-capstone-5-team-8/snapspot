/**
 * How many visits does each geofence strategy catch, and at what cost?
 *
 *   ./gradlew :app:testDebugUnitTest -Psim            # prints the markdown table
 *   ./gradlew :app:testDebugUnitTest -Psim -PsimWrite # also writes docs/simulation.md
 *
 * Drives the real planner (core/Window.kt) over synthetic trips through a city with franchise
 * branches at Seoul-like densities. A visit = the user inside a spot's alert radius for a stretch
 * of time; it counts as caught if that spot was registered with the OS at any moment of the visit
 * (Android fires an initial ENTER for regions you are already in).
 */
package com.capstone.snapspot.sim

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.SEARCH_RADIUS_M
import com.capstone.snapspot.core.Spot
import com.capstone.snapspot.core.Tier
import com.capstone.snapspot.core.cellOf
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.planRegions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private val ORIGIN = Coords(37.5, 126.95)
private const val SIZE_M = 12_000.0
private const val M_PER_DEG_LAT = 111_320.0
private val M_PER_DEG_LNG = 111_320.0 * cos(Math.toRadians(ORIGIN.latitude))
private const val BRANCH_RADIUS_M = 200.0

/** Kakao returns at most this many results per query (3 pages × 15). */
private const val KAKAO_CAP = 45
private const val STEP_S = 10

/** mulberry32, bit-for-bit the same as the original JS simulator, so every run gives the same table. */
private class Rng(seed: Int) {
    private var s = seed
    fun next(): Double {
        s += 0x6d2b79f5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

private val rand = Rng(20261010)

private fun at(x: Double, y: Double) = Coords(ORIGIN.latitude + y / M_PER_DEG_LAT, ORIGIN.longitude + x / M_PER_DEG_LNG)

/** Branch densities per km², roughly Starbucks / Mega Coffee / Olive Young in central Seoul. */
private val BRANDS = listOf("starbucks" to 1.6, "megacoffee" to 3.0, "oliveyoung" to 1.2)
private val AREA_KM2 = (SIZE_M / 1000) * (SIZE_M / 1000)

private val branches: List<Spot> = BRANDS.flatMapIndexed { rank, (key, perKm2) ->
    (0 until (perKm2 * AREA_KM2).roundToInt()).map { i ->
        val x = rand.next() * SIZE_M
        val y = rand.next() * SIZE_M
        val c = at(x, y)
        Spot("b|$key|$i", key, Tier.NORMAL, c.latitude, c.longitude, BRANCH_RADIUS_M, rank)
    }
}
private val places: List<Spot> = (0 until 6).map { i ->
    val x = rand.next() * SIZE_M
    val y = rand.next() * SIZE_M
    val c = at(x, y)
    Spot("p_$i", "place", Tier.PLACE, c.latitude, c.longitude, 200.0)
}

/** What a Kakao query around a grid cell would return for one brand: nearest 45 within 2.5km. */
private fun kakaoSearch(brand: String, here: Coords): List<Spot> {
    val center = cellOf(here).center
    return branches.filter { it.group == brand }
        .map { it to distanceMeters(center, Coords(it.latitude, it.longitude)) }
        .filter { it.second <= SEARCH_RADIUS_M }
        .sortedBy { it.second }
        .take(KAKAO_CAP)
        .map { it.first }
}

private class Trip(val points: List<Coords>, val meters: Double)

/** A trip through 3–5 random waypoints, walking (1.4 m/s) or by bus (6 m/s), sampled every 10s. */
private fun makeTrip(): Trip {
    val speed = if (rand.next() < 0.6) 1.4 else 6.0
    val n = 3 + floor(rand.next() * 3).toInt()
    val way = (0 until n).map {
        val x = 1500 + rand.next() * (SIZE_M - 3000)
        val y = 1500 + rand.next() * (SIZE_M - 3000)
        x to y
    }
    val points = mutableListOf<Coords>()
    var meters = 0.0
    for (w in 0 until way.size - 1) {
        val (ax, ay) = way[w]
        val (bx, by) = way[w + 1]
        val len = hypot(bx - ax, by - ay)
        val steps = max(1, (len / (speed * STEP_S)).roundToInt())
        for (i in 0 until steps) points += at(ax + (bx - ax) * i / steps, ay + (by - ay) * i / steps)
        meters += len
    }
    return Trip(points, meters)
}

private val trips = List(300) { makeTrip() }

private data class Visit(val spot: Spot, val from: Int, val to: Int)

/** Contiguous stretches inside each spot's radius: what the user would want an alert for. */
private fun visits(trip: Trip, spots: List<Spot>): List<Visit> {
    val out = mutableListOf<Visit>()
    for (s in spots) {
        val c = Coords(s.latitude, s.longitude)
        var start = -1
        trip.points.forEachIndexed { t, p ->
            val inside = distanceMeters(p, c) <= s.radius
            if (inside && start < 0) start = t
            if (!inside && start >= 0) {
                out += Visit(s, start, t - 1)
                start = -1
            }
        }
        if (start >= 0) out += Visit(s, start, trip.points.size - 1)
    }
    return out
}

private data class Strategy(
    val name: String,
    val slots: Int,
    /** Re-plan when the user leaves a circle of this radius (null = never). */
    val refreshM: Double?,
    /** Seconds between the exit and the new plan being active (background wake-up latency). */
    val wakeDelayS: Int,
    /** Branches per brand. */
    val cap: Int = 7,
)

private data class Result(
    val caught: Int, val total: Int, val replans: Int, val requests: Int, val km: Double,
    /** Visits to saved places caught / total. */
    val placeCaught: Int, val placeTotal: Int,
    /** (trip, brand) pairs where the trip passed a branch: alerted at least once / total. */
    val brandCaught: Int, val brandTotal: Int,
)

private fun run(st: Strategy): Result {
    var caught = 0
    var total = 0
    var replans = 0
    var requests = 0
    var km = 0.0
    var placeCaught = 0
    var placeTotal = 0
    var brandCaught = 0
    var brandTotal = 0
    val cache = HashSet<String>()
    val allSpots = places + branches
    for (trip in trips) {
        km += trip.meters / 1000
        fun plan(here: Coords): Pair<Set<String>, Coords> {
            val spots = places.toMutableList()
            for ((b, _) in BRANDS) {
                if (cache.add("$b|${cellOf(here).key}")) requests++
                spots += kakaoSearch(b, here)
            }
            val regions = planRegions(here, spots, st.slots, st.refreshM ?: 1.0, st.cap).regions
            return regions.filter { it.identifier != REFRESH_ID }.map { it.identifier }.toSet() to here
        }

        val registered = ArrayList<Set<String>>(trip.points.size)
        var current = plan(trip.points[0])
        replans++
        var pendingAt = -1
        for (t in trip.points.indices) {
            val p = trip.points[t]
            if (pendingAt >= 0 && t >= pendingAt) {
                current = plan(p)
                replans++
                pendingAt = -1
            }
            registered += current.first
            if (st.refreshM != null && pendingAt < 0 && distanceMeters(current.second, p) > st.refreshM) {
                pendingAt = t + ceil(st.wakeDelayS.toDouble() / STEP_S).toInt()
            }
        }
        val brandSeen = HashMap<String, Boolean>()
        for (v in visits(trip, allSpots)) {
            total++
            val hit = (v.from..v.to).any { registered[it].contains(v.spot.id) }
            if (hit) caught++
            if (v.spot.tier == Tier.PLACE) {
                placeTotal++
                if (hit) placeCaught++
            } else {
                brandSeen[v.spot.group] = (brandSeen[v.spot.group] ?: false) || hit
            }
        }
        brandTotal += brandSeen.size
        brandCaught += brandSeen.values.count { it }
    }
    return Result(caught, total, replans, requests, km, placeCaught, placeTotal, brandCaught, brandTotal)
}

private fun pct(a: Int, b: Int) = String.format(Locale.US, "%.1f%%", 100.0 * a / b)

private fun table(rows: List<Pair<Strategy, Result>>) = buildString {
    appendLine("| 전략 | 저장한 장소 | 쿠폰 알림 (지점을 지난 이동 중) | 지점 방문 전체 | 다시 고른 횟수 / km | 카카오 요청 |")
    appendLine("|---|---|---|---|---|---|")
    for ((st, r) in rows) {
        appendLine(
            "| ${st.name} | ${pct(r.placeCaught, r.placeTotal)} (${r.placeCaught}/${r.placeTotal}) | ${pct(r.brandCaught, r.brandTotal)} | " +
                "${pct(r.caught - r.placeCaught, r.total - r.placeTotal)} | ${String.format(Locale.US, "%.2f", r.replans / r.km)} | ${r.requests} |",
        )
    }
}

fun simulationReport(): String {
    val main = listOf(Strategy("고정 (출발 때 한 번, 90칸)", 90, null, 0)) +
        listOf(20, 50, 90).flatMap { slots ->
            listOf(500.0, 1000.0, 2000.0).map { r -> Strategy("움직이는 창 ${slots}칸 · 갱신 ${r.toInt()}m", slots, r, 120) }
        } +
        listOf(
            Strategy("움직이는 창 90칸 · 갱신 1000m · 깨어남 지연 0초", 90, 1000.0, 0),
            Strategy("움직이는 창 90칸 · 갱신 1000m · 깨어남 지연 5분", 90, 1000.0, 300),
        )
    val perBrand = listOf(
        Strategy("브랜드당 3곳", 90, 1000.0, 120, cap = 3),
        Strategy("**브랜드당 7곳 (지금 설정)**", 90, 1000.0, 120, cap = 7),
        Strategy("제한 없음 (이전 버전처럼 칸을 고르게 나눔)", 90, 1000.0, 120, cap = Int.MAX_VALUE),
    )
    val mainRows = main.map { it to run(it) }
    val capRows = perBrand.map { it to run(it) }
    return """## 지오펜스 전략 시뮬레이션

`./gradlew :app:testDebugUnitTest -Psim -PsimWrite`로 다시 만들 수 있어요 (난수 시드 고정, 이전 JS 시뮬레이터와 같은 도시·이동).

- 도시: ${(SIZE_M / 1000).toInt()}km × ${(SIZE_M / 1000).toInt()}km, 브랜드 3개 지점 ${branches.size}곳 (km²당 ${BRANDS.joinToString(" / ") { it.second.toString().removeSuffix(".0") }}곳), 저장한 장소 ${places.size}곳
- 이동: ${trips.size}번, 걷기 60% (1.4m/s) · 버스 40% (6m/s), 총 ${mainRows[0].second.km.roundToInt()}km
- 방문: 장소·지점 반경 안에 들어간 구간. 그 구간 중 한 순간이라도 그곳이 OS에 등록돼 있으면 "잡은 방문"
- **저장한 장소**: 장소 방문 중 잡은 비율. **쿠폰 알림**: 어떤 브랜드 지점을 하나라도 지나간 이동에서, 그 브랜드 알림이 한 번이라도 울린 비율 (쿠폰은 어느 지점이든 한 번 알려 주면 돼요). **지점 방문 전체**: 지점 하나하나의 방문을 다 잡은 비율
- 계획: 지금 앱의 우선순위 규칙 (저장한 장소 먼저, 쿠폰 브랜드는 브랜드당 가장 가까운 7곳, 같은 등급 브랜드끼리 돌아가며)
- 카카오 검색은 실제처럼 격자 칸 중심에서 반경 ${SEARCH_RADIUS_M / 1000.0}km, 최대 ${KAKAO_CAP}곳만 돌려준다고 가정
- 별도 표시가 없으면 갱신 구역을 벗어난 뒤 앱이 깨어나 다시 고르기까지 2분 걸린다고 가정

${table(mainRows)}
### 브랜드당 지점 수 (90칸 · 갱신 1000m · 지연 2분)

브랜드마다 가까운 몇 곳만 등록하면 지도가 깔끔하고 다른 브랜드·장소에 칸이 남지만, 지점이 빽빽한 동네에서는 일부 지점 방문을 놓쳐요.
쿠폰은 "어느 지점이든 한 번 알려 주면 되는" 성격이라 지점 하나하나를 다 잡을 필요는 없어요.

${table(capRows)}"""
}

/** Runs only with -Psim (it takes a while). */
class SimulateWindow {
    @Test
    fun report() {
        assumeTrue(System.getProperty("sim.run") == "true", "run with ./gradlew :app:testDebugUnitTest -Psim")
        val report = simulationReport()
        println(report)
        if (System.getProperty("sim.write") == "true") {
            File(System.getProperty("sim.out")).writeText(report)
            println("wrote ${System.getProperty("sim.out")}")
        }
    }
}
