package com.capstone.snapspot.sim

import com.capstone.snapspot.core.BRANCHES_PER_BRAND
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.MAX_REGIONS
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.Spot
import com.capstone.snapspot.core.Tier
import com.capstone.snapspot.core.planRegions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The refresh-radius question under control: one brand, 50 branches spread evenly over a disk (coordinates only,
 * no coupons or Kakao), people walking inside it, and the app's own planRegions choosing what to register.
 *
 * ./gradlew :app:testDebugUnitTest -PuniformSim [-PuniformWrite]  → docs/refresh-radius.md, docs/sim/uniform-*.svg
 */
class UniformBranches {
    private val origin = Coords(37.5447, 127.0560)
    private val mLat = 111_320.0
    private val mLng = 111_320.0 * cos(Math.toRadians(origin.latitude))
    private fun coords(x: Double, y: Double) = Coords(origin.latitude + y / mLat, origin.longitude + x / mLng)

    private val stepS = 10
    private val walkMps = 1.4
    private val alertM = 200.0
    private val branchCount = 50
    private val people = 500
    private val legs = 5
    private val legMaxM = 1200.0
    private val seeds = listOf(1, 2, 3)
    private val spreads = listOf(5000.0, 3000.0, 1000.0)
    private val refreshes = listOf(250.0, 500.0, 1000.0, 1500.0, 2000.0, 3000.0)
    private val delays = listOf(120, 0)

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

    /** Uniform over a disk of radius r (sqrt keeps the density even). */
    private fun inDisk(rng: Rng, r: Double): Pair<Double, Double> {
        val d = r * sqrt(rng.next())
        val a = 2 * PI * rng.next()
        return d * cos(a) to d * sin(a)
    }

    private class Stats(var passes: Int = 0, var caught: Int = 0, var walks: Int = 0, var alerted: Int = 0, var firstCaught: Int = 0, var replans: Int = 0, var km: Double = 0.0) {
        operator fun plus(o: Stats) = Stats(passes + o.passes, caught + o.caught, walks + o.walks, alerted + o.alerted, firstCaught + o.firstCaught, replans + o.replans, km + o.km)
        val covered get() = 100.0 * caught / passes
        val first get() = 100.0 * firstCaught / walks
        val any get() = 100.0 * alerted / walks
        val perKm get() = replans / km
    }

    private fun run(spread: Double, refreshM: Double, delayS: Int, seed: Int): Stats {
        val rng = Rng(seed)
        val pts = List(branchCount) { inDisk(rng, spread) }
        val spots = pts.mapIndexed { i, (x, y) -> coords(x, y).let { Spot("b$i", "brand", Tier.NORMAL, it.latitude, it.longitude, alertM) } }
        val st = Stats()
        repeat(people) {
            // A stroll: start anywhere, then legs of up to 1.2km, staying inside the disk.
            val stops = mutableListOf(inDisk(rng, spread))
            while (stops.size < legs + 1) {
                val (lx, ly) = stops.last()
                val (dx, dy) = inDisk(rng, legMaxM)
                if (hypot(lx + dx, ly + dy) <= spread) stops += (lx + dx) to (ly + dy)
            }
            val path = mutableListOf<Pair<Double, Double>>()
            for (w in 0 until stops.size - 1) {
                val (ax, ay) = stops[w]
                val (bx, by) = stops[w + 1]
                val len = hypot(bx - ax, by - ay)
                st.km += len / 1000
                val n = max(1, (len / (walkMps * stepS)).roundToInt())
                for (i in 0 until n) path += (ax + (bx - ax) * i / n) to (ay + (by - ay) * i / n)
            }
            path += stops.last()

            fun plan(x: Double, y: Double): Set<String> =
                planRegions(coords(x, y), spots, MAX_REGIONS, refreshM, BRANCHES_PER_BRAND).regions.map { it.identifier }.filter { it != REFRESH_ID }.toSet()

            var reg = plan(path[0].first, path[0].second)
            var at = path[0]
            st.replans++
            var pendingAt = -1
            val registered = ArrayList<Set<String>>(path.size)
            for (t in path.indices) {
                val (x, y) = path[t]
                if (pendingAt in 0..t) {
                    reg = plan(x, y); at = path[t]; st.replans++; pendingAt = -1
                }
                registered += reg
                if (pendingAt < 0 && hypot(x - at.first, y - at.second) > refreshM) pendingAt = t + ceil(delayS.toDouble() / stepS).toInt()
            }

            // Visits: stretches of the path within 200m of a branch.
            class V(val id: String, val from: Int, val to: Int)
            val visits = mutableListOf<V>()
            pts.forEachIndexed { i, (bx, by) ->
                var start = -1
                for (t in path.indices) {
                    val inside = hypot(path[t].first - bx, path[t].second - by) <= alertM
                    if (inside && start < 0) start = t
                    if (!inside && start >= 0) { visits += V("b$i", start, t - 1); start = -1 }
                }
                if (start >= 0) visits += V("b$i", start, path.size - 1)
            }
            if (visits.isEmpty()) return@repeat
            st.walks++
            val hits = visits.map { v -> (v.from..v.to).any { registered[it].contains(v.id) } }
            st.passes += visits.size
            st.caught += hits.count { it }
            if (hits.any { it }) st.alerted++
            if (hits[visits.indices.minBy { visits[it].from }]) st.firstCaught++
        }
        return st
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun density(spread: Double) = branchCount / (PI * (spread / 1000) * (spread / 1000))
    private fun km(m: Double) = if (m < 1000) "${m.toInt()}m" else "${f1(m / 1000).removeSuffix(".0")}km"

    private fun report(): Pair<String, Map<String, String>> {
        val res = HashMap<Triple<Double, Int, Double>, Stats>()
        for (spread in spreads) for (d in delays) for (r in refreshes) res[Triple(spread, d, r)] = seeds.map { run(spread, r, d, 7919 * it) }.reduce(Stats::plus)
        val s = { spread: Double, r: Double -> res.getValue(Triple(spread, 120, r)) }
        val mid = 3000.0
        val xs = refreshes.map { km(it) }

        val charts = mapOf(
            "uniform-first.svg" to lineChart(
                title = "처음 지나간 지점에서 바로 알림이 울린 비율",
                xLabel = "갱신 반경", xs = xs, yLabel = "%",
                series = spreads.mapIndexed { i, sp -> Series("km²당 ${f1(density(sp))}곳", i, refreshes.map { s(sp, it).first }) },
            ),
            "uniform-wakeups.svg" to lineChart(
                title = "갱신 반경별 1km 이동당 갱신 횟수",
                xLabel = "갱신 반경", xs = xs, yLabel = "회/km", percent = false,
                series = listOf(Series("갱신 횟수", 2, refreshes.map { s(mid, it).perKm })),
            ),
        )

        fun table(spread: Double) = buildString {
            appendLine("| 갱신 반경 | 갱신 지연 | 처음 지나간 지점에서 알림 | 알림이 한 번이라도 | 지나간 지점을 감시 중이던 비율 | km당 갱신 |")
            appendLine("|---|---|---|---|---|---|")
            for (d in delays) for (r in refreshes) {
                val x = res.getValue(Triple(spread, d, r))
                val name = if (r == 1000.0 && d == 120) "**1km (지금 설정)**" else km(r)
                appendLine(
                    "| $name | ${if (d == 0) "0초" else "2분"} | ${f1(x.first)}% (${x.firstCaught}/${x.walks}) | ${f1(x.any)}% (${x.alerted}/${x.walks}) | " +
                        "${f1(x.covered)}% (${x.caught}/${x.passes}) | ${f2(x.perKm)} |",
                )
            }
        }

        val m500 = s(mid, 500.0); val m1000 = s(mid, 1000.0); val m1500 = s(mid, 1500.0); val m2000 = s(mid, 2000.0); val m250 = s(mid, 250.0)
        val dense = s(1000.0, 1000.0); val sparse1000 = s(5000.0, 1000.0); val sparse2000 = s(5000.0, 2000.0); val sparse3000 = s(5000.0, 3000.0)
        val md = """## 갱신 반경: 왜 1km인가

`./gradlew :app:testDebugUnitTest -PuniformSim -PuniformWrite`로 다시 만들 수 있어요 (난수 시드 고정).

### 무엇을 정하는 값인가

휴대폰은 한 번에 정해진 수(앱은 ${MAX_REGIONS}칸)만 지오펜스로 감시할 수 있어요. 그래서 앱은 **지금 자리에서 가까운 지점을 브랜드마다 ${BRANCHES_PER_BRAND}곳씩** 골라 등록하고, 사용자가 그 자리에서 **갱신 반경**만큼 멀어지면 새 자리 기준으로 다시 골라요.

- 반경이 작으면: 등록된 지점이 늘 지금 위치 근처라 놓치지 않지만, 자주 위치를 다시 받고 카카오를 다시 검색해요 (배터리, API 호출).
- 반경이 크면: 갱신은 드물지만, 멀어진 뒤에는 "아까 자리 근처" 지점만 등록돼 있어서 지금 지나가는 지점을 놓쳐요.

이 실험은 그 사이 어디가 적당한지를, 지점 배치를 단순하게 통제해서 봐요.

### 실험 설정

- **지점**: 한 브랜드 지점 ${branchCount}곳을 원 안에 고르게 뿌려요 (위도·경도만, 쿠폰이나 카카오 검색 없음). 원 반경을 바꿔 밀도를 세 가지로 봐요.

  | 원 반경 | km²당 지점 | 서울에서 비슷한 경우 |
  |---|---|---|
  | 1km | ${f1(density(1000.0))}곳 | 번화가 한가운데 (강남역·홍대 앞) |
  | 3km | ${f1(density(3000.0))}곳 | 메가커피 서울 평균 (1282곳 ÷ 605km² ≈ 2.1곳) |
  | 5km | ${f1(density(5000.0))}곳 | 올리브영 서울 평균 (434곳 ÷ 605km² ≈ 0.7곳) |

- **사람**: 원 안 아무 곳에서 출발해 최대 ${km(legMaxM)}씩 ${legs}번 걸어요 (${walkMps}m/s). ${people}명 × 시드 ${seeds.size}개
- **등록 규칙**: 앱의 `planRegions`를 그대로 써요 (${MAX_REGIONS}칸, 브랜드당 가까운 ${BRANCHES_PER_BRAND}곳)
- **갱신 지연**: 갱신 반경을 벗어난 뒤 새 목록이 등록되기까지 2분 (아래 표에 0초도 있어요)
- **지점 근처를 지났다**: 지점 200m 안에 들어갔다 나온 한 번. 그동안 한 순간이라도 그 지점이 등록돼 있으면 알림이 울려요

### 지표

- **처음 지나간 지점에서 알림** (주 지표): 산책 중 처음 지나간 지점에서 바로 알림이 울린 비율. 쿠폰은 브랜드마다 한 번 울리면 되지만, 처음 지점을 놓치면 다음 지점까지 늦어져요
- **알림이 한 번이라도**: 산책 중 어느 지점에서든 한 번 울린 비율
- **지나간 지점을 감시 중이던 비율**: 지나간 모든 지점 중 그 순간 등록돼 있던 비율. 가장 엄격하지만 사용자가 받는 알림과는 달라요 (아래 "주의" 참고)
- 괄호는 (해당 수 / 전체 수)예요. 지점을 하나라도 지난 산책만 셌어요

### 결과

![처음 지나간 지점에서 바로 알림이 울린 비율](sim/uniform-first.svg)
![갱신 반경별 1km 이동당 갱신 횟수](sim/uniform-wakeups.svg)

첫 그래프의 세로축은 ${(kotlin.math.floor((spreads.minOf { sp -> refreshes.minOf { s(sp, it).first } } - 2) / 10) * 10).toInt()}%에서 시작해요. 차이를 보기 쉽게 확대한 거라, 아래 표의 실제 수치와 함께 봐 주세요.

### 해석: 왜 1km인가

**서울 평균 밀도(원 반경 3km, km²당 ${f1(density(mid))}곳, 지연 2분)를 기준으로:**

| 갱신 반경 | 처음 지나간 지점에서 알림 | km당 갱신 |
|---|---|---|
${refreshes.joinToString("\n") { r -> val x = s(mid, r); "| ${if (r == 1000.0) "**1km**" else km(r)} | ${f1(x.first)}% | ${f2(x.perKm)} |" }}

1. **1km까지는 알림을 거의 잃지 않아요.** 처음 지나간 지점에서 울리는 비율이 250m ${f1(m250.first)}%, 500m ${f1(m500.first)}%, 1km ${f1(m1000.first)}%예요.
2. **1km를 넘으면 눈에 띄게 떨어져요.** 1.5km ${f1(m1500.first)}%, 2km ${f1(m2000.first)}%예요. 열 번 중 한 번꼴로 첫 지점을 놓치는 셈이에요.
3. **갱신은 1km에서 이미 크게 줄어요.** 500m보다 ${f1(m500.perKm / m1000.perKm)}배, 250m보다 ${f1(m250.perKm / m1000.perKm)}배 적게 갱신해요. km당 갱신이 250m→500m에서 ${f2(m250.perKm - m500.perKm)}회, 500m→1km에서 ${f2(m500.perKm - m1000.perKm)}회 줄고, 1km→2km는 ${f2(m1000.perKm - m2000.perKm)}회 줄어요. 반경을 키울수록 아끼는 양은 작아지는데, 1km를 넘으면 알림은 눈에 띄게 잃어요.
4. **그래서 1km가 꺾이는 지점이에요.** 알림 손실 없이 갱신을 가장 많이 줄일 수 있는 값이에요.

**밀도가 달라도 1km는 안전해요.**

- **번화가 (km²당 ${f1(density(1000.0))}곳)**: 1km에서도 처음 지점 알림 ${f1(dense.first)}%예요. 근처에 지점이 많아 등록된 ${BRANCHES_PER_BRAND}곳 중 하나는 거의 늘 지나가요.
- **드문 곳 (km²당 ${f1(density(5000.0))}곳)**: 1km ${f1(sparse1000.first)}%, 2km ${f1(sparse2000.first)}%, 3km ${f1(sparse3000.first)}%예요. 지점이 드물면 더 키워도 버티지만, 1km에서도 손해는 없어요.
- 실제 앱은 사용자 주변 밀도를 미리 알 수 없어요. **어느 밀도에서도 알림을 잃지 않는 가장 큰 값**으로 1km를 골랐어요.

### 주의

- **"지나간 지점을 감시 중이던 비율"은 번화가에서 낮게 나와요** (1km에서 ${f1(dense.covered)}%). 지점은 많은데 브랜드당 ${BRANCHES_PER_BRAND}곳만 등록하니까요. 그래도 그중 하나는 거의 늘 지나가서, 사용자가 받는 알림(${f1(dense.first)}%)에는 영향이 없어요. 이 지표만 보면 결론을 잘못 읽어요.
- **갱신 지연이 결과를 바꿔요.** 2분은 가정값이에요. 실제 기기에서 갱신에 걸리는 시간을 재서 다시 확인해야 해요.
- **걷기만 봤어요.** 버스로 지나가는 알림은 앱이 일부러 미루기 때문에 여기서는 빼요. 실제 서울 데이터와 버스를 섞은 결과는 [시뮬레이션 보고서](simulation.md)에 있어요.
- **브랜드 하나만 넣었어요.** 브랜드가 여러 개여도 칸(${MAX_REGIONS}칸)이 넉넉하면 브랜드마다 같은 규칙이 따로 적용돼서 결과는 같아요.

### 전체 표

#### 원 반경 3km (km²당 ${f1(density(3000.0))}곳, 서울 평균)

${table(3000.0)}
#### 원 반경 1km (km²당 ${f1(density(1000.0))}곳, 번화가)

${table(1000.0)}
#### 원 반경 5km (km²당 ${f1(density(5000.0))}곳, 드문 곳)

${table(5000.0)}"""
        return md to charts
    }

    @Test
    fun report_() {
        assumeTrue(System.getProperty("uniform.run") == "true", "run with ./gradlew :app:testDebugUnitTest -PuniformSim")
        val (md, charts) = report()
        println(md)
        if (System.getProperty("uniform.write") == "true") {
            val out = File(requireNotNull(System.getProperty("uniform.out")))
            out.writeText(md)
            val dir = File(out.parentFile, "sim").apply { mkdirs() }
            charts.forEach { (name, svg) -> File(dir, name).writeText(svg) }
            println("wrote $out and ${charts.size} charts in $dir")
        }
    }
}
