/**
 * How many visits does each geofence strategy catch, and at what cost?
 *
 *   ./gradlew :app:testDebugUnitTest -Psim            # prints the report
 *   ./gradlew :app:testDebugUnitTest -Psim -PsimWrite # also writes docs/simulation.md and the SVG charts in docs/sim
 *
 * Drives the real planner (core/Window.kt) over simulated days in Seoul, built on real data
 * (sim/seoul.json, see FetchSimData): 160 cafés, restaurants and venues in 8 neighborhoods people
 * screenshot, and every Starbucks, Mega Coffee and Olive Young branch Kakao lists. Simulated users
 * live at random points, ride the bus between neighborhoods and walk around inside them.
 *
 * A visit = the user inside a spot's alert radius for a stretch of time; it counts as caught if
 * that spot was registered with the OS at any moment of the visit (Android fires an initial ENTER
 * for regions you are already in). Every configuration runs on three seeds; tables show the mean
 * and the range.
 */
package com.capstone.snapspot.sim

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.SEARCH_RADIUS_M
import com.capstone.snapspot.core.Spot
import com.capstone.snapspot.core.Tier
import com.capstone.snapspot.core.VEHICLE_SPEED_MPS
import com.capstone.snapspot.core.cellOf
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.planRegions
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private val CENTER = Coords(37.55, 126.99)
private const val M_PER_DEG_LAT = 111_320.0
private val M_PER_DEG_LNG = 111_320.0 * cos(Math.toRadians(CENTER.latitude))
private const val ALERT_RADIUS_M = 200.0

/** Kakao returns at most this many results per query (3 pages × 15). */
private const val KAKAO_CAP = 45
private const val STEP_S = 10
private const val WALK_MPS = 1.4
private const val BUS_MPS = 6.0

/** Farther than this between two stops: take the bus. */
private const val WALK_MAX_M = 1500.0

private val SEEDS = listOf(20261010, 20261011, 20261012)
private const val USERS = 40
private const val TRIPS_PER_USER = 5

/** mulberry32: a small seeded generator, so every run gives the same report. */
private class Rng(seed: Int) {
    private var s = seed
    fun next(): Double {
        s += 0x6d2b79f5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
    fun pick(n: Int) = (next() * n).toInt().coerceAtMost(n - 1)
}

private data class Xy(val x: Double, val y: Double)

private fun xy(c: Coords) = Xy((c.longitude - CENTER.longitude) * M_PER_DEG_LNG, (c.latitude - CENTER.latitude) * M_PER_DEG_LAT)
private fun coords(p: Xy) = Coords(CENTER.latitude + p.y / M_PER_DEG_LAT, CENTER.longitude + p.x / M_PER_DEG_LNG)

private val simJson = Json { ignoreUnknownKeys = true }

private val data: SimData by lazy {
    val text = SimulateWindow::class.java.classLoader!!.getResource("sim/seoul.json")!!.readText()
    simJson.decodeFromString(SimData.serializer(), text)
}

private val branches: List<Spot> by lazy {
    SIM_BRANDS.flatMapIndexed { rank, (_, key) ->
        data.branches.getValue(key).mapIndexed { i, b -> Spot("b|$key|$i", key, Tier.NORMAL, b.lat, b.lng, ALERT_RADIUS_M, rank) }
    }
}
private val placePool: List<Spot> by lazy {
    data.places.mapIndexed { i, p -> Spot("p_$i", "place", Tier.PLACE, p.lat, p.lng, ALERT_RADIUS_M) }
}
private val spotXy: Map<String, Xy> by lazy { (branches + placePool).associate { it.id to xy(Coords(it.latitude, it.longitude)) } }

/** What a Kakao query around a grid cell returns for one brand: the nearest 45 within 2.5km. Same for every run. */
private val kakaoCache = HashMap<String, List<Spot>>()
private fun kakaoSearch(brand: String, here: Coords): List<Spot> = kakaoCache.getOrPut("$brand|${cellOf(here).key}") {
    val center = cellOf(here).center
    branches.filter { it.group == brand }
        .map { it to distanceMeters(center, Coords(it.latitude, it.longitude)) }
        .filter { it.second <= SEARCH_RADIUS_M }
        .sortedBy { it.second }
        .take(KAKAO_CAP)
        .map { it.first }
}

/** Points every 10s, and whether the user was on the bus at each one. */
private class Trip(val points: List<Xy>, val bus: List<Boolean>, val meters: Double)

private class Visit(val spot: Spot, val from: Int, val to: Int, val bus: Boolean)

/** A simulated person: where they live, the places they saved (in the order they saved them) and their days out. */
private class User(val saved: List<Spot>, val trips: List<Trip>, val visits: List<List<Visit>>)

private fun tripOf(stops: List<Xy>): Trip {
    val points = mutableListOf<Xy>()
    val bus = mutableListOf<Boolean>()
    var meters = 0.0
    for (w in 0 until stops.size - 1) {
        val a = stops[w]
        val b = stops[w + 1]
        val len = hypot(b.x - a.x, b.y - a.y)
        val onBus = len > WALK_MAX_M
        val steps = max(1, (len / ((if (onBus) BUS_MPS else WALK_MPS) * STEP_S)).roundToInt())
        for (i in 0 until steps) {
            points += Xy(a.x + (b.x - a.x) * i / steps, a.y + (b.y - a.y) * i / steps)
            bus += onBus
        }
        meters += len
    }
    points += stops.last()
    bus += false
    return Trip(points, bus, meters)
}

/** A random point within `r` meters of `c`. */
private fun near(rand: Rng, c: Xy, r: Double): Xy {
    val a = rand.next() * 2 * Math.PI
    val d = r * Math.sqrt(rand.next())
    return Xy(c.x + d * cos(a), c.y + d * sin(a))
}

/** Home → a neighborhood, walk around it → sometimes a second one → home. */
private fun day(rand: Rng, home: Xy): Trip {
    val stops = mutableListOf(home)
    val hoods = data.hoods.map { xy(Coords(it.lat, it.lng)) }
    val first = hoods[rand.pick(hoods.size)]
    repeat(4) { stops += near(rand, first, 700.0) }
    if (rand.next() < 0.4) {
        val second = hoods[rand.pick(hoods.size)]
        repeat(3) { stops += near(rand, second, 700.0) }
    }
    stops += home
    return tripOf(stops)
}

/** Stretches inside each spot's radius. Only spots near the trip are checked. */
private fun visits(trip: Trip, spots: List<Spot>): List<Visit> {
    val minX = trip.points.minOf { it.x } - ALERT_RADIUS_M
    val maxX = trip.points.maxOf { it.x } + ALERT_RADIUS_M
    val minY = trip.points.minOf { it.y } - ALERT_RADIUS_M
    val maxY = trip.points.maxOf { it.y } + ALERT_RADIUS_M
    val out = mutableListOf<Visit>()
    for (s in spots) {
        val c = spotXy.getValue(s.id)
        if (c.x < minX || c.x > maxX || c.y < minY || c.y > maxY) continue
        var start = -1
        trip.points.forEachIndexed { t, p ->
            val inside = hypot(p.x - c.x, p.y - c.y) <= s.radius
            if (inside && start < 0) start = t
            if (!inside && start >= 0) {
                out += Visit(s, start, t - 1, trip.bus[start])
                start = -1
            }
        }
        if (start >= 0) out += Visit(s, start, trip.points.size - 1, trip.bus[start])
    }
    return out
}

private val users: Map<Int, List<User>> by lazy {
    val minX = data.hoods.minOf { xy(Coords(it.lat, it.lng)).x } - 3000
    val maxX = data.hoods.maxOf { xy(Coords(it.lat, it.lng)).x } + 3000
    val minY = data.hoods.minOf { xy(Coords(it.lat, it.lng)).y } - 3000
    val maxY = data.hoods.maxOf { xy(Coords(it.lat, it.lng)).y } + 3000
    SEEDS.associateWith { seed ->
        val rand = Rng(seed)
        List(USERS) {
            val home = Xy(minX + rand.next() * (maxX - minX), minY + rand.next() * (maxY - minY))
            // Saved places in the order they were saved: the first K are "K saved places".
            val saved = placePool.toMutableList().apply {
                for (i in indices.reversed()) {
                    val j = rand.pick(i + 1)
                    this[i] = this[j].also { this[j] = this[i] }
                }
            }
            val trips = List(TRIPS_PER_USER) { day(rand, home) }
            User(saved, trips, trips.map { visits(it, branches + placePool) })
        }
    }
}

private data class Strategy(
    val name: String,
    val slots: Int,
    /** Re-plan when the user leaves a circle of this radius (null = never). */
    val refreshM: Double?,
    /** Seconds between the exit and the new plan being active (background wake-up latency). */
    val wakeDelayS: Int = 120,
    /** Branches per brand. */
    val cap: Int = 7,
    /** Saved places per user. */
    val saved: Int = 30,
)

private data class Result(
    val placeCaught: Int, val placeTotal: Int,
    /** (trip, brand) pairs where the trip passed a branch: alerted at least once / total. */
    val brandCaught: Int, val brandTotal: Int,
    val branchCaught: Int, val branchTotal: Int,
    val replans: Int, val km: Double, val requests: Int,
    /** Alerts the app would post, and those that came while on the bus. */
    val alerts: Int, val busAlerts: Int,
) {
    val place get() = 100.0 * placeCaught / placeTotal
    val brand get() = 100.0 * brandCaught / brandTotal
    val branch get() = 100.0 * branchCaught / branchTotal
    val replansPerKm get() = replans / km
    val bus get() = 100.0 * busAlerts / alerts
}

private fun run(st: Strategy, seed: Int): Result {
    var placeCaught = 0; var placeTotal = 0; var brandCaught = 0; var brandTotal = 0
    var branchCaught = 0; var branchTotal = 0; var replans = 0; var km = 0.0
    var alerts = 0; var busAlerts = 0
    val cells = HashSet<String>()
    for (user in users.getValue(seed)) {
        val saved = user.saved.take(st.saved)
        val savedIds = saved.map { it.id }.toSet()
        user.trips.forEachIndexed { ti, trip ->
            km += trip.meters / 1000
            fun plan(here: Coords): Pair<Set<String>, Xy> {
                val spots = saved.toMutableList()
                for ((_, b) in SIM_BRANDS) {
                    cells += "$b|${cellOf(here).key}"
                    spots += kakaoSearch(b, here)
                }
                val regions = planRegions(here, spots, st.slots, st.refreshM ?: 1.0, st.cap).regions
                return regions.filter { it.identifier != REFRESH_ID }.map { it.identifier }.toSet() to xy(here)
            }

            val registered = ArrayList<Set<String>>(trip.points.size)
            var current = plan(coords(trip.points[0]))
            replans++
            var pendingAt = -1
            for (t in trip.points.indices) {
                val p = trip.points[t]
                if (pendingAt in 0..t) {
                    current = plan(coords(p))
                    replans++
                    pendingAt = -1
                }
                registered += current.first
                val c = current.second
                if (st.refreshM != null && pendingAt < 0 && hypot(c.x - p.x, c.y - p.y) > st.refreshM) {
                    pendingAt = t + ceil(st.wakeDelayS.toDouble() / STEP_S).toInt()
                }
            }

            // One coupon alert per brand per trip: its earliest caught visit.
            val brandsSeen = HashSet<String>()
            val firstHit = HashMap<String, Visit>()
            for (v in user.visits[ti]) {
                val isPlace = v.spot.tier == Tier.PLACE
                if (isPlace && v.spot.id !in savedIds) continue
                val hit = (v.from..v.to).any { registered[it].contains(v.spot.id) }
                if (isPlace) {
                    placeTotal++
                    if (hit) {
                        placeCaught++
                        alerts++
                        if (v.bus) busAlerts++
                    }
                } else {
                    branchTotal++
                    if (hit) branchCaught++
                    brandsSeen += v.spot.group
                    if (hit && (firstHit[v.spot.group]?.from ?: Int.MAX_VALUE) > v.from) firstHit[v.spot.group] = v
                }
            }
            brandTotal += brandsSeen.size
            brandCaught += firstHit.size
            alerts += firstHit.size
            busAlerts += firstHit.values.count { it.bus }
        }
    }
    return Result(placeCaught, placeTotal, brandCaught, brandTotal, branchCaught, branchTotal, replans, km, cells.size, alerts, busAlerts)
}

/** One strategy over every seed. */
private class Row(val st: Strategy, val results: List<Result>) {
    fun stat(f: (Result) -> Double) = results.map(f).let { Triple(it.average(), it.min(), it.max()) }
    fun pct(f: (Result) -> Double): String {
        val (avg, lo, hi) = stat(f)
        return if (hi - lo < 0.05) f1(avg) + "%" else "${f1(avg)}% (${f1(lo)}–${f1(hi)})"
    }
}

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

private fun rows(strategies: List<Strategy>) = strategies.map { st -> Row(st, SEEDS.map { run(st, it) }) }

private fun table(rows: List<Row>) = buildString {
    appendLine("| 전략 | 저장한 장소 | 쿠폰 알림 (지점을 지난 이동 중) | 지점 방문 전체 | 다시 고른 횟수 / km | 버스로 지나가며 울린 알림 |")
    appendLine("|---|---|---|---|---|---|")
    for (r in rows) {
        val sum = { f: (Result) -> Int -> r.results.sumOf(f) }
        appendLine(
            "| ${r.st.name} | ${r.pct { it.place }} (${sum { it.placeCaught }}/${sum { it.placeTotal }}) | ${r.pct { it.brand }} | " +
                "${r.pct { it.branch }} | ${f2(r.stat { it.replansPerKm }.first)} | ${f1(r.stat { it.bus }.first)}% |",
        )
    }
}

class SimReport(val markdown: String, val charts: Map<String, String>)

fun simulationReport(): SimReport {
    // "On the bus" here must mean what the app's vehicle rule means.
    check(BUS_MPS >= VEHICLE_SPEED_MPS && WALK_MPS < VEHICLE_SPEED_MPS)
    val refreshes = listOf(500.0, 1000.0, 2000.0)
    val slotSets = listOf(20, 50, 90)
    val grid = slotSets.flatMap { s -> refreshes.map { r -> Strategy("움직이는 창 ${s}칸 · 갱신 ${r.toInt()}m", s, r) } }
    val main = listOf(Strategy("고정 (출발 때 한 번, 90칸)", 90, null, 0)) + grid + listOf(
        Strategy("움직이는 창 90칸 · 갱신 1000m · 깨어남 지연 0초", 90, 1000.0, 0),
        Strategy("움직이는 창 90칸 · 갱신 1000m · 깨어남 지연 5분", 90, 1000.0, 300),
    )
    val perBrand = listOf(
        Strategy("브랜드당 3곳", 90, 1000.0, cap = 3),
        Strategy("**브랜드당 7곳 (지금 설정)**", 90, 1000.0, cap = 7),
        Strategy("제한 없음", 90, 1000.0, cap = Int.MAX_VALUE),
    )
    val savedCounts = listOf(10, 30, 60)
    val bySaved = listOf(20, 90).flatMap { s -> savedCounts.map { k -> Strategy("${s}칸 · 저장한 장소 ${k}곳", s, 1000.0, saved = k) } }

    val mainRows = rows(main)
    val capRows = rows(perBrand)
    val savedRows = rows(bySaved)
    val gridRow = { s: Int, r: Double -> mainRows.first { it.st.slots == s && it.st.refreshM == r && it.st.wakeDelayS == 120 } }
    val first = mainRows[0].results[0]
    val trips = USERS * TRIPS_PER_USER

    val charts = mapOf(
        "refresh.svg" to lineChart(
            title = "갱신 반경별 지점 방문을 잡은 비율 (저장한 장소 30곳)",
            xLabel = "갱신 반경", xs = refreshes.map { "${it.toInt()}m" }, yLabel = "%",
            // 20 slots register no branches at all with 30 saved places (see saved.svg), so that line would sit at 0.
            series = listOf(50, 90).mapIndexed { i, s -> Series("${s}칸", i + 1, refreshes.map { r -> gridRow(s, r).stat { it.branch }.first }) },
        ),
        "wakeups.svg" to lineChart(
            title = "갱신 반경별 앱이 깨어난 횟수 (km당)",
            xLabel = "갱신 반경", xs = refreshes.map { "${it.toInt()}m" }, yLabel = "회/km", percent = false,
            series = listOf(Series("깨어난 횟수", 2, refreshes.map { r -> gridRow(90, r).stat { it.replansPerKm }.first })),
        ),
        "saved.svg" to lineChart(
            title = "저장한 장소 수별 쿠폰 알림을 받은 비율 (갱신 1000m)",
            xLabel = "저장한 장소 수", xs = savedCounts.map { "${it}곳" }, yLabel = "%",
            series = listOf(20, 90).mapIndexed { i, s -> Series("${s}칸", i * 2, savedCounts.map { k -> savedRows.first { it.st.slots == s && it.st.saved == k }.stat { it.brand }.first }) },
        ),
    )

    val md = """## 지오펜스 전략 시뮬레이션

`./gradlew :app:testDebugUnitTest -Psim -PsimWrite`로 다시 만들 수 있어요 (난수 시드 고정).

### 가정

- **실제 서울 데이터** (${data.source}, `app/src/test/resources/sim/seoul.json`)
  - 저장할 만한 장소 ${data.places.size}곳: ${data.hoods.joinToString(", ") { it.name }} 8개 동네의 카페·음식점·전시/공연장 (동네마다 20곳)
  - 쿠폰 지점: ${SIM_BRANDS.joinToString(", ") { (q, k) -> "$q ${data.branches.getValue(k).size}곳" }}
- **사용자 ${USERS}명 × 하루 ${TRIPS_PER_USER}번 외출**, 시드 ${SEEDS.size}개로 반복 (총 ${trips * SEEDS.size}번 외출, 시드당 약 ${first.km.roundToInt()}km)
  - 집은 동네들을 둘러싼 지역의 아무 곳. 외출은 한 동네에 가서 네 곳을 걸어 다니고, 40%는 다른 동네에 한 번 더 들렀다가 집으로
  - ${WALK_MAX_M.toInt() / 1000.0}km보다 먼 구간은 버스(${BUS_MPS}m/s), 나머지는 걷기(${WALK_MPS}m/s)
  - 저장한 장소는 사람마다 ${data.places.size}곳 중 무작위. 기본 30곳이고, 10곳은 30곳에, 30곳은 60곳에 포함돼요
- **방문**: 장소·지점 반경(200m) 안에 들어간 구간. 그 구간 중 한 순간이라도 그곳이 OS에 등록돼 있으면 "잡은 방문"
- **쿠폰 알림**: 어떤 브랜드 지점을 하나라도 지나간 외출에서, 그 브랜드 알림이 한 번이라도 울린 비율. **지점 방문 전체**: 지점 하나하나의 방문을 다 잡은 비율
- **버스로 지나가며 울린 알림**: 지금 앱은 이 알림을 미뤘다가, 근처에서 내려 걸을 때만 울려요
- 카카오 검색은 실제처럼 격자 칸 중심에서 반경 ${SEARCH_RADIUS_M / 1000.0}km, 최대 ${KAKAO_CAP}곳만 돌려준다고 가정. 깨어나 다시 고르기까지 별도 표시가 없으면 2분
- 수치는 시드 3개의 평균이고, 괄호는 최솟값–최댓값이에요 (차이가 거의 없으면 생략)

### 갱신 반경과 칸 수

![갱신 반경별 지점 방문을 잡은 비율](sim/refresh.svg)
![갱신 반경별 앱이 깨어난 횟수](sim/wakeups.svg)

${table(mainRows)}
저장한 장소는 모든 설정에서 놓친 방문이 없어요. 장소가 우선순위 1순위라 가까운 순으로 먼저 칸을 차지해서, 근처에 가면 늘 등록돼 있어요. 설정에 따라 달라지는 건 쿠폰 쪽이에요.

### 저장한 장소 수 (갱신 1000m)

칸이 적은 iOS 한도(20칸)에서 저장한 장소가 늘면, 장소가 칸을 모두 차지해서 쿠폰 지점이 한 곳도 등록되지 못해요. Android(90칸)에서는 60곳을 저장해도 쿠폰 알림이 그대로예요. iOS로 넓힐 때는 쿠폰 몫으로 칸을 몇 개 남겨 두는 규칙이 필요해요.

![저장한 장소 수별 쿠폰 알림을 받은 비율](sim/saved.svg)

${table(savedRows)}
### 브랜드당 지점 수 (90칸 · 갱신 1000m · 지연 2분)

쿠폰은 "어느 지점이든 한 번 알려 주면 되는" 성격이라 지점 하나하나를 다 잡을 필요는 없어요. 브랜드마다 가까운 몇 곳만 등록하면 남는 칸이 저장한 장소와 다른 브랜드에 돌아가요.

${table(capRows)}"""
    return SimReport(md, charts)
}

/** Runs only with -Psim (it takes a while). */
class SimulateWindow {
    @Test
    fun report() {
        assumeTrue(System.getProperty("sim.run") == "true", "run with ./gradlew :app:testDebugUnitTest -Psim")
        val report = simulationReport()
        println(report.markdown)
        if (System.getProperty("sim.write") == "true") {
            val out = File(requireNotNull(System.getProperty("sim.out")))
            out.writeText(report.markdown)
            val dir = File(out.parentFile, "sim").apply { mkdirs() }
            report.charts.forEach { (name, svg) -> File(dir, name).writeText(svg) }
            println("wrote $out and ${report.charts.size} charts in $dir")
        }
    }
}
