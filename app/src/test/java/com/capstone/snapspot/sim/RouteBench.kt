/**
 * Route algorithm benchmark on real Seoul places (sim/seoul.json): how long each algorithm takes
 * and how far the heuristic lands from the optimum, for n = 4…20 places in one neighborhood.
 *
 *   ./gradlew :app:testDebugUnitTest -ProuteBench               # prints the report
 *   ./gradlew :app:testDebugUnitTest -ProuteBench -ProuteWrite  # also writes docs/route.md and its charts
 */
package com.capstone.snapspot.sim

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.EXACT_MAX
import com.capstone.snapspot.core.bruteForceRoute
import com.capstone.snapspot.core.exactRoute
import com.capstone.snapspot.core.heuristicRoute
import com.capstone.snapspot.core.Stop
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.lowerBound
import com.capstone.snapspot.core.stopsOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

private const val REPEATS = 30
private val NS = (4..20).toList()
/** Brute force is timed up to here (fewer runs above [BRUTE_FULL_RUNS_MAX]); the chart extends it to n = 20 as an estimate. */
private const val BRUTE_MAX = 13
private const val HELD_KARP_MAX = 20
/** The brute-force estimate is drawn up to about three hours. */
private const val BRUTE_DRAW_MAX_MS = 1e7
/**
 * Above these n a single run takes up to seconds, so timing uses a few cases and a short warm-up. Held-Karp keeps
 * full runs through the sizes that decide [EXACT_MAX].
 */
private const val BRUTE_FULL_RUNS_MAX = 11
private const val HELD_KARP_FULL_RUNS_MAX = 16
private const val BUDGET_MS = 50.0

/** Phones run this several times slower than a desktop JVM; the cutoff keeps the phone under the budget. */
private const val DEVICE_SLOWDOWN = 10.0

private class Case(val points: List<Coords>, val start: Coords) {
    val stops: List<Stop> get() = stopsOf(points)
}

private fun cases(n: Int): List<Case> {
    val text = RouteBench::class.java.classLoader!!.getResource("sim/seoul.json")!!.readText()
    val data = Json { ignoreUnknownKeys = true }.decodeFromString(SimData.serializer(), text)
    val byHood = data.places.groupBy { it.hood }.values.toList()
    val r = Random(20261013 + n)
    return List(REPEATS) {
        val hood = byHood[r.nextInt(byHood.size)]
        val picked = hood.shuffled(r).take(n).map { Coords(it.lat, it.lng) }
        val c = Coords(picked.map { it.latitude }.average(), picked.map { it.longitude }.average())
        Case(picked, Coords(c.latitude + (r.nextDouble() - 0.5) * 0.004, c.longitude + (r.nextDouble() - 0.5) * 0.005))
    }
}

/** Median wall time in ms of `f` over the cases, after `warm` runs to warm the JIT. */
private fun timeMs(cs: List<Case>, warm: Int = 20, f: (Case) -> Unit): Double {
    repeat(warm) { f(cs[it % cs.size]) }
    val times = cs.map { c -> val t = System.nanoTime(); f(c); (System.nanoTime() - t) / 1e6 }.sorted()
    return times[times.size / 2]
}

/** "0.01ms", "3.5ms", "1.5초", "17분", "28시간", "46일", "52년": durations a reader can feel. */
private fun duration(ms: Double): String {
    fun g(v: Double) = String.format(Locale.US, if (v < 10) "%.1f" else "%.0f", v).removeSuffix(".0")
    val s = ms / 1000
    return when {
        ms < 1 -> String.format(Locale.US, "%.3f", ms).trimEnd('0').trimEnd('.') + "ms"
        ms < 1000 -> g(ms) + "ms"
        s < 60 -> g(s) + "초"
        s < 3600 -> g(s / 60) + "분"
        s < 48 * 3600 -> g(s / 3600) + "시간"
        s < 365 * 86400 -> g(s / 86400) + "일"
        else -> String.format(Locale.US, "%,.0f", s / (365 * 86400)) + "년"
    }
}

class RouteBench {
    @Test
    fun bench() {
        assumeTrue(System.getProperty("route.run") == "true", "run with ./gradlew :app:testDebugUnitTest -ProuteBench")
        data class Row(val n: Int, val brute: Double?, val hk: Double?, val heur: Double, val gapNn: Double, val gap2: Double, val gapAll: Double, val vsMst: Boolean)
        val rows = NS.map { n ->
            val cs = cases(n)
            // Slow sizes: a few cases and a short warm-up keep the whole run to minutes.
            fun time(fullUpTo: Int, f: (Case) -> Unit) = if (n <= fullUpTo) timeMs(cs, f = f) else timeMs(cs.take(5), warm = 2, f = f)
            val brute = if (n <= BRUTE_MAX) time(BRUTE_FULL_RUNS_MAX) { bruteForceRoute(it.stops, it.start) } else null
            val hk = if (n <= HELD_KARP_MAX) time(HELD_KARP_FULL_RUNS_MAX) { exactRoute(it.stops, it.start) } else null
            val heur = timeMs(cs) { heuristicRoute(it.stops, it.start) }
            // Against the optimum while Held-Karp can give it; above that against the MST lower bound (an upper bound on the gap).
            val vsMst = n > HELD_KARP_MAX
            val refs = cs.map { c -> if (vsMst) lowerBound(c.stops, c.start) else exactRoute(c.stops, c.start).meters }
            fun gap(f: (Case) -> Double) = cs.indices.map { i ->
                val c = cs[i]
                val ref = refs[i]
                100 * (f(c) / ref - 1)
            }.average()
            Row(
                n, brute, hk, heur,
                gap { heuristicRoute(it.stops, it.start, twoOpt = false, orOpt = false).meters },
                gap { heuristicRoute(it.stops, it.start, twoOpt = true, orOpt = false).meters },
                gap { heuristicRoute(it.stops, it.start).meters },
                vsMst,
            )
        }
        val coupons = couponExperiment()
        val cutoff = rows.filter { it.hk != null && it.hk * DEVICE_SLOWDOWN <= BUDGET_MS }.maxOf { it.n }
        fun ms(v: Double?) = v?.let { String.format(Locale.US, if (it < 1) "%.3f" else "%.1f", it) } ?: "–"
        fun pc(v: Double) = String.format(Locale.US, "%.1f%%", v)
        val table = buildString {
            appendLine("| 장소 수 n | 완전 탐색 (ms) | Held-Karp (ms) | 2-opt·Or-opt (ms) | 최근접 이웃 | + 2-opt | + Or-opt | 비교 기준 |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (r in rows) appendLine("| ${r.n} | ${ms(r.brute)} | ${ms(r.hk)} | ${ms(r.heur)} | ${pc(r.gapNn)} | ${pc(r.gap2)} | ${pc(r.gapAll)} | ${if (r.vsMst) "MST 하한" else "최적 (Held-Karp)"} |")
        }
        // Both charts mark where the app switches from the exact route to the heuristic.
        val switchAt = NS.indexOf(EXACT_MAX)
        // Brute force beyond what can be timed: n!·n work, scaled from the last measured size.
        val bruteRows = rows.filter { it.brute != null }
        val bruteLast = bruteRows.last()
        fun work(n: Int) = (1..n).fold(1.0) { a, k -> a * k } * n
        // Drawn only up to a few hours so the measured lines keep their room.
        val bruteEstimate = NS.filter { it > bruteLast.n }.map { n -> bruteLast.brute!! * work(n) / work(bruteLast.n) }
            .takeWhile { it <= BRUTE_DRAW_MAX_MS }
        val timeChart = lineChart(
            title = "장소 수별 계산 시간 (PC 측정, 중앙값)", xLabel = "장소 수 n", xs = NS.map { "$it" }, yLabel = "",
            subtitle = "세로축 한 칸 = 10배. 점선은 추정(n!에 비례)",
            series = listOf(
                Series(
                    "완전 탐색", 0, bruteRows.map { it.brute!! },
                    label = "완전 탐색 (추정)", estimate = bruteEstimate,
                ),
                Series("Held-Karp", 1, rows.filter { it.hk != null }.map { it.hk!! }),
                Series("2-opt·Or-opt", 2, rows.map { it.heur }),
            ), percent = false, logY = true, palette = Palette.CATEGORICAL, format = ::duration,
            refLine = RefLine(BUDGET_MS / DEVICE_SLOWDOWN, "PC ${(BUDGET_MS / DEVICE_SLOWDOWN).toInt()}ms = 폰 ${BUDGET_MS.toInt()}ms 한도", atStart = true),
            split = Split(switchAt, "앱: ${EXACT_MAX}곳까지 Held-Karp", "${EXACT_MAX + 1}곳부터 2-opt·Or-opt"),
        )
        val gapChart = lineChart(
            title = "방법별 동선 길이 (정답 대비, 정답 = Held-Karp)", xLabel = "장소 수 n", xs = NS.filter { it <= HELD_KARP_MAX }.map { "$it" }, yLabel = "%",
            subtitle = "Held-Karp는 정답이라 늘 0%. 최근접 이웃에 2-opt, Or-opt를 차례로 더해요(앱은 ③까지)",
            series = listOf(
                Series("① 최근접 이웃", 0, rows.filter { !it.vsMst }.map { it.gapNn }),
                Series("② + 2-opt", 1, rows.filter { !it.vsMst }.map { it.gap2 }),
                Series("③ + Or-opt", 2, rows.filter { !it.vsMst }.map { it.gapAll }),
                Series("Held-Karp (정답)", 3, rows.filter { !it.vsMst }.map { 0.0 }),
            ),
            split = Split(switchAt, "앱은 정답 사용", "2-opt·Or-opt 사용"),
            // Held-Karp sits on 0%: lift it off the axis so the line reads as a line.
            bottomPad = 2.0,
        )
        val md = routeDoc(table, cutoff, coupons)
        println(md)
        if (System.getProperty("route.write") == "true") {
            val out = File(requireNotNull(System.getProperty("route.out")))
            out.writeText(md)
            val dir = File(out.parentFile, "route").apply { mkdirs() }
            File(dir, "time.svg").writeText(timeChart)
            File(dir, "gap.svg").writeText(gapChart)
        }
        println("cutoff (Held-Karp × $DEVICE_SLOWDOWN ≤ ${BUDGET_MS}ms): n = $cutoff; app uses EXACT_MAX = $EXACT_MAX")
    }
}

/**
 * Coupon stops: 5–8 places in one neighborhood plus 1–2 brands whose real branches lie within 500m
 * of a place (up to 8 per brand). Naive = take each brand's branch nearest the places' center, then
 * solve the plain route; GTSP = let the exact route pick the branch. Returns the markdown table.
 */
private fun couponExperiment(): String {
    val text = RouteBench::class.java.classLoader!!.getResource("sim/seoul.json")!!.readText()
    val data = Json { ignoreUnknownKeys = true }.decodeFromString(SimData.serializer(), text)
    val byHood = data.places.groupBy { it.hood }.values.toList()
    val brands = SIM_BRANDS.map { (_, key) -> data.branches.getValue(key).map { Coords(it.lat, it.lng) } }
    val r = Random(20261014)
    data class Trial(val saving: Double, val naiveMs: Double, val gtspMs: Double, val brands: Int)
    val trials = mutableListOf<Trial>()
    while (trials.size < 60) {
        val places = byHood[r.nextInt(byHood.size)].shuffled(r).take(5 + r.nextInt(4)).map { Coords(it.lat, it.lng) }
        val center = Coords(places.map { it.latitude }.average(), places.map { it.longitude }.average())
        val nearBrands = brands.map { b -> b.filter { br -> places.any { distanceMeters(it, br) <= 500 } }.sortedBy { distanceMeters(center, it) }.take(8) }
            .filter { it.size >= 2 }.shuffled(r).take(1 + r.nextInt(2))
        if (nearBrands.isEmpty()) continue
        val start = Coords(center.latitude + (r.nextDouble() - 0.5) * 0.004, center.longitude + (r.nextDouble() - 0.5) * 0.005)
        var naive = 0.0
        var t = System.nanoTime()
        naive = exactRoute(stopsOf(places + nearBrands.map { it.first() }), start).meters
        val naiveMs = (System.nanoTime() - t) / 1e6
        t = System.nanoTime()
        val gtsp = exactRoute(stopsOf(places) + nearBrands, start).meters
        val gtspMs = (System.nanoTime() - t) / 1e6
        trials += Trial(100 * (1 - gtsp / naive), naiveMs, gtspMs, nearBrands.size)
    }
    fun f(v: Double) = String.format(Locale.US, "%.1f", v)
    return buildString {
        appendLine("| 쿠폰 브랜드 수 | 경우 | 지점 선택 최적화로 줄어든 거리 (평균) | 최대 | 단순 방법 (ms, 중앙값) | 지점 선택 최적화 (ms, 중앙값) |")
        appendLine("|---|---|---|---|---|---|")
        for ((k, ts) in trials.groupBy { it.brands }.toSortedMap()) {
            appendLine("| $k | ${ts.size} | ${f(ts.map { it.saving }.average())}% | ${f(ts.maxOf { it.saving })}% | ${f(ts.map { it.naiveMs }.sorted()[ts.size / 2])} | ${f(ts.map { it.gtspMs }.sorted()[ts.size / 2])} |")
        }
    }
}

private fun routeDoc(table: String, cutoff: Int, coupons: String) = """## 동네 묶음 최단 경로

동네 묶음의 걷기 동선은 **출발점이 있고 돌아오지 않는 외판원 문제(Open-path TSP)**예요. 모든 장소를 한 번씩 들르는 가장 짧은 순서를 찾아요.

`./gradlew :app:testDebugUnitTest -ProuteBench -ProuteWrite`로 다시 만들 수 있어요 (시드 고정).

### 알고리즘

| 방법 | 시간 복잡도 (g = 들를 곳 수, N = 후보 수) | 정확도 | 앱에서 쓰는 곳 |
|---|---|---|---|
| 완전 탐색 | O(g!·∏kᵢ) | 최적 | 테스트에서 정답 확인용 |
| **Held-Karp 동적 계획법** | O(2^g·N²), 메모리 O(2^g·N) (쿠폰 없으면 O(n²·2ⁿ)) | 최적 | 들를 곳 ${EXACT_MAX}곳 이하 |
| **최근접 이웃 + 2-opt + Or-opt + 지점 다시 고르기** | 한 바퀴 O(g³ + Σkᵢkᵢ₊₁) (구현), 몇 바퀴 반복 | 보장 없음 (휴리스틱) | ${EXACT_MAX + 1}곳 이상 |
| 최소 신장 트리(MST, Prim) | O(g²·k²) | 하한 | 정답을 구할 수 없을 만큼 클 때 휴리스틱의 오차 상한 계산 |

#### 각 방법을 쉽게 말하면

내 위치에서 출발해 장소 A, B, C, D를 한 번씩 들르는 가장 짧은 순서를 찾는다고 해 볼게요.

- **완전 탐색**: 가능한 순서(A→B→C→D, A→B→D→C, …)를 전부 재 보고 가장 짧은 걸 골라요. 답은 확실하지만 순서의 수가 n!이라 한 곳 늘 때마다 대략 n배 느려져요. 그래서 앱에서는 안 쓰고, 다른 방법이 맞는 답을 내는지 확인하는 정답지로만 써요.
- **Held-Karp 동적 계획법**: "A, B, C를 들렀고 지금 C에 있다"처럼 **상황이 같은 경로는 짧은 것 하나만 기억**해요. S→A→B→C와 S→B→A→C는 앞으로 갈 길이 똑같으니 긴 쪽은 버려요. 그래서 순서 전체(n!) 대신 "들른 곳 집합 × 지금 위치"(2ⁿ·n칸)만 채우면 돼요. 정확한 답을 주지만 2ⁿ으로 늘어서 ${EXACT_MAX}곳까지만 써요.
  - 쿠폰 지점이 있으면 집합은 브랜드 단위로 체크하고, "지금 위치"는 실제 지점으로 둬요. 그래서 순서와 들를 지점을 한 번에 정해요.
- **2-opt·Or-opt 휴리스틱** (${EXACT_MAX + 1}곳 이상). 정답을 보장하지 않는 대신 빨라요(그래서 "근사 알고리즘"이 아니라 휴리스틱이에요). 아래 그래프의 ①②③이 이 단계들이에요.
  - **① 최근접 이웃 (Nearest Neighbor)**: 지금 위치에서 가장 가까운 안 간 곳으로 계속 가요. 빠르지만 10% 정도 길게 나와요.
  - **② 2-opt**: 동선에 X자로 엇갈린 두 선이 있으면 그 사이 구간을 뒤집어 풀어요. 엇갈린 선을 풀면 항상 짧아져요.
  - **③ Or-opt**: 1~3곳을 떼어 동선의 다른 자리에 끼워 보고, 짧아지면 옮겨요.
  - **지점 다시 고르기**: 순서를 정해 둔 채로 브랜드마다 어느 지점이 가장 짧은지 다시 골라요. ②, ③과 이 단계를 더 줄지 않을 때까지 번갈아 해요.
- **MST 하한**: 모든 장소를 선으로 잇는 가장 짧은 방법(최소 신장 트리)이에요. 한 줄로 잇는 동선도 "모두 이은 모양"의 하나라서 어떤 동선도 이보다 짧을 수 없어요. 정답을 구할 수 없는 큰 n에서 "휴리스틱이 최대 몇 % 길다"를 재는 바닥으로 써요. 이번 측정은 20곳까지 Held-Karp로 정답을 구할 수 있어서 쓰지 않았어요.

### 실험

- 데이터: 실제 서울 8개 동네의 장소 160곳(`sim/seoul.json`). 동네 하나에서 n곳을 무작위로 뽑고, 출발점은 그 장소들 중심 근처의 임의 위치. n마다 ${REPEATS}번.
- 시간: JVM 예열 후 중앙값. 완전 탐색은 n ≤ ${BRUTE_MAX}, Held-Karp는 n ≤ ${HELD_KARP_MAX}까지 쟀어요. 한 번에 오래 걸리는 크기(완전 탐색 n > ${BRUTE_FULL_RUNS_MAX}, Held-Karp n > ${HELD_KARP_FULL_RUNS_MAX})는 사례 5개로 쟀어요. 그래프의 완전 탐색 점선(n > ${BRUTE_MAX})은 마지막 측정값에서 계산량(n!·n)에 비례해 늘린 **추정**이에요.
- 거리 차이: ${if (HELD_KARP_MAX >= NS.last()) "모든 n에서 Held-Karp 최적해와 비교한 평균이에요." else "n ≤ ${HELD_KARP_MAX}는 Held-Karp 최적해와 비교한 평균. 그보다 크면 MST 하한과 비교한 값이라 실제 차이보다 큰 **상한**이에요(MST 하한은 최적보다 보통 15~20% 짧아요)."}

![장소 수별 실행 시간](route/time.svg)
![방법별 동선 길이 (Held-Karp의 정답 대비)](route/gap.svg)

$table
### 쿠폰 쓸 곳 포함: 지점 고르기

"쿠폰 쓸 곳 포함"을 켜면 쿠폰 브랜드마다 근처 지점 중 **한 곳만** 들르면 돼요. 이건 무리마다 한 곳을 고르는 **일반화 외판원 문제(Generalized TSP)**예요. Held-Karp의 상태를 (들른 무리 집합, 마지막으로 들른 지점)으로 넓혀 O(2^g·N²)에 정확히 풀어요(g = 무리 수, N = 전체 후보 수).

비교: 브랜드마다 장소들 중심에서 가장 가까운 지점을 미리 고르는 **단순 방법** 대 **지점 선택 최적화**. 동네에서 장소 5~8곳과 실제 지점(장소에서 500m 안, 브랜드마다 최대 8곳)을 60번 뽑았어요.

$coupons
### 결론

- 폰은 이 측정(데스크톱 JVM)보다 최대 ${DEVICE_SLOWDOWN.toInt()}배 느리다고 보고, 폰에서도 ${BUDGET_MS.toInt()}ms 안에 끝나도록 Held-Karp가 ${(BUDGET_MS / DEVICE_SLOWDOWN).toInt()}ms 안에 끝나는 가장 큰 n을 봤어요. 이번 측정에서는 **$cutoff**이에요. 시간 측정은 실행마다 조금씩 흔들려서 이 값도 실행마다 달라져요(지금까지 12~13). 그래서 앱의 전환 기준 `EXACT_MAX`는 여유를 두고 ${EXACT_MAX}로 정했어요.
- 한 동네에 저장하는 장소는 보통 10곳 이하라, 대부분의 묶음은 정확한 최단 경로를 받아요.
"""
