# Neighborhood Bundles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Group saved places that sit within walking distance into named neighborhood bundles, show each bundle on the home screen with the shortest walking route, and send one bundle alert instead of one alert per place.

**Architecture:** Pure logic in `core/`: `Route.kt` solves the open-path TSP (brute force for checking, Held-Karp DP exact, nearest neighbor + 2-opt + Or-opt heuristic, MST lower bound) and `Bundles.kt` clusters places (700m single linkage, 3+ places) and builds alert text. Bundles are recomputed from saved places, never stored. Region names come from Kakao `coord2regioncode` and are cached per grid cell in their own file. A benchmark test on real Seoul places measures every algorithm and picks the exact/heuristic cutoff.

**Tech Stack:** Kotlin 2.2, Jetpack Compose, Kakao Map SDK v2.15.2 (`ShapeLayer.addPolyline`), OkHttp, kotlinx.serialization, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-13-neighborhood-bundles-design.md`

## Global Constraints

- Link distance 700m (straight line), single linkage; a bundle needs 3 or more places; ended pop-ups (`placeEnded`) are excluded.
- Route: open path, no return. Start from the user's position if it is within 1km of any bundle place, otherwise free start.
- Walking minutes = ceil(meters × 1.3 ÷ 70). Shown on the home screen only.
- Bundle alert copy: title `📍 {동네}에 가 볼 곳 {n}곳이 있어요`, body `{entered}, {next} 외 {n-2}곳` (no time, no route). Deep link `snapspot://bundle/{enteredPlaceId}`.
- Bundle cooldown: no bundle alert if any member was notified within 6 hours (`PLACE_COOLDOWN_MS`); a sent bundle alert marks every member notified. On-screen silence and vehicle holds still apply.
- Do not modify `data/Repository.kt` or `core/model/Models.kt` (team contract files).
- Region name: Kakao `GET /v2/local/geo/coord2regioncode.json`, `region_type == "H"`, `region_3depth_name`, digits stripped by the spec's rule; cached per `cellOf` cell in `region-names.json`; the alert path reads the cache only.
- No Claude attribution in commits or PRs.
- Run unit tests from `C:\Users\hasan\dev\snapspot` (Git Bash): `./gradlew :app:testDebugUnitTest --console=plain -q`; results in `app/build/test-results/testDebugUnitTest/*.xml`.

## Review Focus

- A bundle alert's place was deleted (or the bundle shrank below 3) before the user taps the notification → the app opens home and focuses the place if it still exists; it must not crash. → Task 6 `openBundle` falls back to `focusOn`; Task 2 test `bundleOf returns null when the place is no longer in a bundle`.
- Two saved places at the exact same coordinates (the same café saved twice before dedup) → route still valid, zero-length legs. → Task 1 test `duplicate points are fine`.
- A member was alerted on its own within the last 6 hours (before it joined a bundle) → no bundle alert yet. → Task 5 test `a member alerted recently holds the bundle alert back`.
- Position unknown (no fix yet) → bundles still listed, route uses a free start. → Task 1 test `free start is never worse than any fixed first place`; Task 6 passes `null`.
- Region names with no digits or only "동" → shown unchanged. → Task 2 test `region names lose their numbers`.
- "쿠폰 쓸 곳 포함" on with no branch within 500m, or the branch fetch failing → the route stays places-only, and a failure is shown on the card. → Task 6 `toggleCoupons` stores `null` for a failed fetch (`couponsFailed`) and drops brands with no nearby branch.

---

### Task 1: Route algorithms (generalized TSP)

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/core/Route.kt`
- Test: `app/src/test/java/com/capstone/snapspot/core/RouteTest.kt`

**Interfaces:**
- Consumes: `Coords`, `distanceMeters(a: Coords, b: Coords): Double` (`core/Distance.kt`).
- Produces:
  - `typealias Stop = List<Coords>` — the options for one stop: a saved place has one, a coupon brand has its nearby branches.
  - `data class RouteResult(val order: List<Int>, val picks: List<Int>, val meters: Double)` — `order[i]` is a stop index, `picks[i]` the option index chosen in that stop.
  - `fun bruteForceRoute(stops: List<Stop>, start: Coords?): RouteResult`
  - `fun exactRoute(stops: List<Stop>, start: Coords?): RouteResult` (Held-Karp over stops)
  - `fun heuristicRoute(stops: List<Stop>, start: Coords?, twoOpt: Boolean = true, orOpt: Boolean = true, repick: Boolean = true): RouteResult`
  - `fun lowerBound(stops: List<Stop>, start: Coords?): Double` (MST over stops)
  - `fun shortestRoute(stops: List<Stop>, start: Coords?): RouteResult`
  - `fun stopsOf(points: List<Coords>): List<Stop>` (one option each)
  - `fun walkMinutes(meters: Double): Int`
  - `const val EXACT_MAX = 10` (Task 3 replaces the value with the benchmark's cutoff).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RouteTest {
    private val origin = Coords(37.5446, 127.0560)

    private fun near(r: Random) = Coords(origin.latitude + (r.nextDouble() - 0.5) * 0.011, origin.longitude + (r.nextDouble() - 0.5) * 0.014)

    /** `n` stops with 1..`maxOptions` options each, reproducible. */
    private fun stops(n: Int, seed: Int, maxOptions: Int = 1): List<Stop> {
        val r = Random(seed)
        return List(n) { List(1 + r.nextInt(maxOptions)) { near(r) } }
    }

    private fun length(stops: List<Stop>, start: Coords?, res: RouteResult): Double {
        val path = listOfNotNull(start) + res.order.indices.map { stops[res.order[it]][res.picks[it]] }
        return path.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
    }

    @Test
    fun `exact matches brute force, with and without branch choices`() {
        for (seed in 1..30) {
            val st = stops(3 + seed % 5, seed, maxOptions = if (seed % 2 == 0) 3 else 1)
            val start = if (seed % 3 == 0) null else origin
            val exact = exactRoute(st, start)
            assertEquals(bruteForceRoute(st, start).meters, exact.meters, 1e-6, "seed $seed")
            assertEquals(exact.meters, length(st, start, exact), 1e-6)
            assertEquals(st.indices.toSet(), exact.order.toSet())
        }
    }

    @Test
    fun `picks the branch that keeps the walk short`() {
        val a = origin
        val b = Coords(origin.latitude + 0.0036, origin.longitude)                  // ~400m north
        val onTheWay = Coords(origin.latitude + 0.0018, origin.longitude + 0.0011)  // ~100m off the middle
        val farAway = Coords(origin.latitude + 0.0018, origin.longitude + 0.0113)   // ~1km east
        val st = listOf(listOf(a), listOf(b), listOf(farAway, onTheWay))
        for (res in listOf(exactRoute(st, a), heuristicRoute(st, a))) {
            assertEquals(1, res.picks[res.order.indexOf(2)])
        }
    }

    @Test
    fun `heuristic is never shorter than exact and never below the lower bound`() {
        for (seed in 1..30) {
            val st = stops(4 + seed % 8, seed, maxOptions = 1 + seed % 3)
            val start = if (seed % 3 == 0) null else origin
            val exact = exactRoute(st, start).meters
            val h = heuristicRoute(st, start)
            assertTrue(h.meters >= exact - 1e-6, "seed $seed")
            assertTrue(lowerBound(st, start) <= exact + 1e-6, "seed $seed")
            assertEquals(h.meters, length(st, start, h), 1e-6)
        }
    }

    @Test
    fun `each improvement step only shortens the route`() {
        for (seed in 1..25) {
            val st = stops(12 + seed % 9, seed, maxOptions = 3)
            val nn = heuristicRoute(st, origin, twoOpt = false, orOpt = false, repick = false).meters
            // Each local search only accepts shorter routes; they may stop at different local optima.
            for (res in listOf(
                heuristicRoute(st, origin, twoOpt = true, orOpt = false, repick = false),
                heuristicRoute(st, origin, twoOpt = false, orOpt = true, repick = false),
                heuristicRoute(st, origin, twoOpt = false, orOpt = false, repick = true),
                heuristicRoute(st, origin),
            )) assertTrue(res.meters <= nn + 1e-6, "seed $seed")
        }
    }

    @Test
    fun `free start is never worse than any fixed first place`() {
        val pts = List(7) { near(Random(it)) }
        val free = exactRoute(stopsOf(pts), null).meters
        for (i in pts.indices) assertTrue(free <= exactRoute(stopsOf(pts.filterIndexed { j, _ -> j != i }), pts[i]).meters + 1e-6)
    }

    @Test
    fun `small inputs`() {
        assertEquals(RouteResult(emptyList(), emptyList(), 0.0), shortestRoute(emptyList(), origin))
        val one = shortestRoute(stopsOf(listOf(origin)), null)
        assertEquals(listOf(0), one.order)
        assertEquals(0.0, one.meters)
        assertEquals(2, shortestRoute(stops(2, 9), null).order.size)
    }

    @Test
    fun `duplicate points are fine`() {
        val far = Coords(origin.latitude + 0.002, origin.longitude)
        val st = stopsOf(listOf(origin, origin, far, origin))
        val r = exactRoute(st, null)
        assertEquals(4, r.order.size)
        assertEquals(distanceMeters(origin, far), r.meters, 1e-6)
        assertEquals(r.meters, heuristicRoute(st, null).meters, 1e-6)
    }

    @Test
    fun `shortest route switches to the heuristic above EXACT_MAX`() {
        val big = stops(EXACT_MAX + 5, 11)
        assertEquals(heuristicRoute(big, origin).meters, shortestRoute(big, origin).meters, 1e-6)
        val small = stops(EXACT_MAX, 11)
        assertEquals(exactRoute(small, origin).meters, shortestRoute(small, origin).meters, 1e-6)
    }

    @Test
    fun `walking minutes use the detour factor and round up`() {
        assertEquals(0, walkMinutes(0.0))
        assertEquals(2, walkMinutes(100.0))   // 130m / 70 = 1.86
        assertEquals(19, walkMinutes(1000.0)) // 1300 / 70 = 18.57
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RouteTest*" --console=plain -q 2>&1 | grep "^e:" | head -3`
Expected: `Unresolved reference 'Stop'` / `'exactRoute'`.

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * Shortest walking order through a neighborhood: a generalized traveling salesman problem on an
 * open path (start somewhere, no return). Every stop is visited once, at exactly one of its
 * options: a saved place has one option, a coupon brand has its nearby branches, and the route
 * picks the branch that keeps the walk shortest. With one option per stop this is the plain TSP.
 * Straight-line distances. g = stops, N = options in total.
 *
 * - [bruteForceRoute]: every stop order × every pick. Ground truth for tests and the benchmark only.
 * - [exactRoute]: Held-Karp DP over (visited stops bitmask, last option). O(2^g·N²) time, O(2^g·N) memory.
 * - [heuristicRoute]: nearest neighbor, then alternate (a) 2-opt / Or-opt on the stop order and
 *   (b) re-picking every stop's option for that order by a layered shortest path, until neither helps.
 * - [lowerBound]: MST over stops where two stops are as far apart as their closest options; no path
 *   through one option per stop can be shorter, so it bounds the heuristic's gap without the optimum.
 *
 * [EXACT_MAX] (picked by RouteBench, see docs/route.md) decides which one the app uses.
 */
package com.capstone.snapspot.core

import kotlin.math.ceil

/** Up to this many stops the app solves exactly; above it, the heuristic. */
const val EXACT_MAX = 10

/** Streets are not straight: walked distance ≈ straight distance × this. */
const val DETOUR_FACTOR = 1.3
const val WALK_M_PER_MIN = 70.0

/** The options for one stop: one for a saved place, several for a coupon brand's branches. */
typealias Stop = List<Coords>

/** `order[i]`: stop index; `picks[i]`: the option chosen in that stop. `meters` includes the leg from the start. */
data class RouteResult(val order: List<Int>, val picks: List<Int>, val meters: Double)

fun walkMinutes(meters: Double): Int = ceil(meters * DETOUR_FACTOR / WALK_M_PER_MIN).toInt()

fun stopsOf(points: List<Coords>): List<Stop> = points.map { listOf(it) }

/** Options flattened to node ids; node n is the start when there is one. */
private class Graph(stops: List<Stop>, start: Coords?) {
    val g = stops.size
    val offset = IntArray(g + 1).also { for (i in 0 until g) it[i + 1] = it[i] + stops[i].size }
    val n = offset[g]
    val stopOf = IntArray(n).also { for (s in 0 until g) for (k in offset[s] until offset[s + 1]) it[k] = s }
    private val all = stops.flatten() + listOfNotNull(start)
    private val d = Array(all.size) { i -> DoubleArray(all.size) { j -> distanceMeters(all[i], all[j]) } }
    val hasStart = start != null
    operator fun get(i: Int, j: Int) = d[i][j]
    fun lead(node: Int) = if (hasStart) d[n][node] else 0.0
    fun length(nodes: List<Int>): Double =
        if (nodes.isEmpty()) 0.0 else lead(nodes[0]) + nodes.zipWithNext().sumOf { (a, b) -> d[a][b] }
    fun result(nodes: List<Int>) = RouteResult(nodes.map { stopOf[it] }, nodes.map { it - offset[stopOf[it]] }, length(nodes))
    fun nodesOf(stop: Int) = offset[stop] until offset[stop + 1]
}

fun bruteForceRoute(stops: List<Stop>, start: Coords?): RouteResult {
    if (stops.isEmpty()) return RouteResult(emptyList(), emptyList(), 0.0)
    val gr = Graph(stops, start)
    var best: List<Int> = emptyList()
    var bestLen = Double.MAX_VALUE
    val path = mutableListOf<Int>()
    val used = BooleanArray(gr.g)
    fun go(len: Double) {
        if (len >= bestLen) return
        if (path.size == gr.g) {
            best = path.toList()
            bestLen = len
            return
        }
        for (s in 0 until gr.g) {
            if (used[s]) continue
            used[s] = true
            for (v in gr.nodesOf(s)) {
                val add = if (path.isEmpty()) gr.lead(v) else gr[path.last(), v]
                path += v
                go(len + add)
                path.removeAt(path.size - 1)
            }
            used[s] = false
        }
    }
    go(0.0)
    return gr.result(best)
}

fun exactRoute(stops: List<Stop>, start: Coords?): RouteResult {
    if (stops.isEmpty()) return RouteResult(emptyList(), emptyList(), 0.0)
    val gr = Graph(stops, start)
    val n = gr.n
    val full = (1 shl gr.g) - 1
    // cost[mask * n + v]: shortest path through exactly the stops in `mask`, ending at option v.
    val cost = DoubleArray((full + 1) * n) { Double.MAX_VALUE }
    val prev = IntArray((full + 1) * n) { -1 }
    for (v in 0 until n) cost[(1 shl gr.stopOf[v]) * n + v] = gr.lead(v)
    for (mask in 1..full) {
        for (v in 0 until n) {
            val here = cost[mask * n + v]
            if (here == Double.MAX_VALUE) continue
            for (u in 0 until n) {
                val bit = 1 shl gr.stopOf[u]
                if (mask and bit != 0) continue
                val m2 = mask or bit
                val c = here + gr[v, u]
                if (c < cost[m2 * n + u]) {
                    cost[m2 * n + u] = c
                    prev[m2 * n + u] = v
                }
            }
        }
    }
    var v = (0 until n).minBy { cost[full * n + it] }
    val nodes = ArrayDeque<Int>()
    var mask = full
    while (v >= 0) {
        nodes.addFirst(v)
        val p = prev[mask * n + v]
        mask = mask xor (1 shl gr.stopOf[v])
        v = p
    }
    return gr.result(nodes.toList())
}

fun heuristicRoute(stops: List<Stop>, start: Coords?, twoOpt: Boolean = true, orOpt: Boolean = true, repick: Boolean = true): RouteResult {
    if (stops.isEmpty()) return RouteResult(emptyList(), emptyList(), 0.0)
    val gr = Graph(stops, start)
    // With a start, one run from it; without, every option as the first one and keep the best.
    val firsts = if (start != null) listOf(-1) else (0 until gr.n).toList()
    var best: List<Int> = emptyList()
    var bestLen = Double.MAX_VALUE
    for (first in firsts) {
        val nodes = nearestNeighbor(gr, first)
        improve(gr, nodes, twoOpt, orOpt, repick)
        val len = gr.length(nodes)
        if (len < bestLen) {
            best = nodes.toList()
            bestLen = len
        }
    }
    return gr.result(best)
}

/** `first` = -1: from the start point. Next is always the nearest option of a stop not yet visited. */
private fun nearestNeighbor(gr: Graph, first: Int): MutableList<Int> {
    val visited = BooleanArray(gr.g)
    val nodes = mutableListOf<Int>()
    if (first >= 0) {
        nodes += first
        visited[gr.stopOf[first]] = true
    }
    while (nodes.size < gr.g) {
        val next = (0 until gr.n).filter { !visited[gr.stopOf[it]] }
            .minBy { if (nodes.isEmpty()) gr.lead(it) else gr[nodes.last(), it] }
        nodes += next
        visited[gr.stopOf[next]] = true
    }
    return nodes
}

/** Alternates order moves (2-opt, Or-opt) and option re-picking until nothing shortens the route. */
private fun improve(gr: Graph, nodes: MutableList<Int>, twoOpt: Boolean, orOpt: Boolean, repick: Boolean) {
    // Without a start point each run fixes its own first stop; with one, every position may move.
    val lo = if (gr.hasStart) 0 else 1
    var improved = true
    while (improved) {
        improved = false
        if (twoOpt) {
            for (i in lo until nodes.size - 1) for (k in i + 1 until nodes.size) {
                val before = gr.length(nodes)
                nodes.subList(i, k + 1).reverse()
                if (gr.length(nodes) < before - 1e-9) improved = true else nodes.subList(i, k + 1).reverse()
            }
        }
        if (orOpt) {
            for (len in 1..3) {
                var i = lo
                while (i + len <= nodes.size) {
                    val before = gr.length(nodes)
                    val seg = nodes.subList(i, i + len).toList()
                    repeat(len) { nodes.removeAt(i) }
                    var bestPos = -1
                    var bestLen = before - 1e-9
                    for (pos in lo..nodes.size) {
                        nodes.addAll(pos, seg)
                        val l = gr.length(nodes)
                        if (l < bestLen) {
                            bestLen = l
                            bestPos = pos
                        }
                        repeat(len) { nodes.removeAt(pos) }
                    }
                    if (bestPos >= 0) {
                        nodes.addAll(bestPos, seg)
                        improved = true
                    } else {
                        nodes.addAll(i, seg)
                    }
                    i++
                }
            }
        }
        if (repick && repickOptions(gr, nodes)) improved = true
    }
}

/**
 * For the current stop order, the best option in every stop at once: a shortest path through a
 * layered graph (layer i = the options of the i-th stop). O(Σ kᵢ·kᵢ₊₁). True if it shortened the route.
 */
private fun repickOptions(gr: Graph, nodes: MutableList<Int>): Boolean {
    if (nodes.isEmpty()) return false
    val before = gr.length(nodes)
    val layers = nodes.map { gr.nodesOf(gr.stopOf[it]).toList() }
    var cost: Map<Int, Double> = layers[0].associateWith { gr.lead(it) }
    val back = mutableListOf<Map<Int, Int>>()
    for (i in 1 until layers.size) {
        val next = HashMap<Int, Double>()
        val from = HashMap<Int, Int>()
        for (u in layers[i]) {
            val v = cost.keys.minBy { cost.getValue(it) + gr[it, u] }
            next[u] = cost.getValue(v) + gr[v, u]
            from[u] = v
        }
        cost = next
        back += from
    }
    var u = cost.keys.minBy { cost.getValue(it) }
    if (cost.getValue(u) >= before - 1e-9) return false
    val picked = ArrayDeque<Int>().apply { addFirst(u) }
    for (i in back.indices.reversed()) {
        u = back[i].getValue(u)
        picked.addFirst(u)
    }
    for (i in nodes.indices) nodes[i] = picked[i]
    return true
}

/** Prim's MST over stops (plus the start), two stops as far apart as their closest options. */
fun lowerBound(stops: List<Stop>, start: Coords?): Double {
    val all = stops + listOfNotNull(start).map { listOf(it) }
    if (all.size < 2) return 0.0
    fun gap(a: Stop, b: Stop) = a.minOf { p -> b.minOf { q -> distanceMeters(p, q) } }
    val inTree = BooleanArray(all.size)
    val best = DoubleArray(all.size) { Double.MAX_VALUE }
    best[0] = 0.0
    var total = 0.0
    repeat(all.size) {
        val u = all.indices.filter { !inTree[it] }.minBy { best[it] }
        inTree[u] = true
        total += best[u]
        for (v in all.indices) if (!inTree[v]) best[v] = minOf(best[v], gap(all[u], all[v]))
    }
    return total
}

fun shortestRoute(stops: List<Stop>, start: Coords?): RouteResult =
    if (stops.size <= EXACT_MAX) exactRoute(stops, start) else heuristicRoute(stops, start)
```

Note on `improve`: `gr.length(nodes)` is O(g) per move, so a pass is O(g³). It is simple and correct; Task 3's benchmark measures it. If the heuristic median exceeds 50ms at g = 20, switch to O(1) delta formulas and keep these tests.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*RouteTest*" --console=plain -q; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.core.RouteTest.xml`
Expected: `tests="9" skipped="0" failures="0"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/core/Route.kt app/src/test/java/com/capstone/snapspot/core/RouteTest.kt
git commit -m "feat(core): shortest walking route as a generalized TSP (Held-Karp, 2-opt/Or-opt + re-pick, MST bound)"
```

---

### Task 2: Bundles and their text

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/core/Bundles.kt`
- Test: `app/src/test/java/com/capstone/snapspot/core/BundlesTest.kt`

**Interfaces:**
- Consumes: `Place` (`core/model/Models.kt`), `placeEnded(p: Place, now: Long)` (`core/Coupons.kt`), `distanceMeters`, test helper `place(id, name, latitude, longitude, endDate, lastNotifiedAt)` (`core/AlertsTest.kt`), `NOW`.
- Produces:
  - `const val BUNDLE_LINK_M = 700.0`, `const val BUNDLE_MIN = 3`
  - `data class Bundle(val id: String, val places: List<Place>)` with `val center: Coords`
  - `fun bundles(places: List<Place>, now: Long, here: Coords? = null): List<Bundle>`
  - `fun bundleOf(places: List<Place>, placeId: String, now: Long): Bundle?`
  - `fun regionDisplayName(raw: String): String`
  - `fun fallbackBundleName(b: Bundle): String`
  - `fun bundleAlertText(name: String, b: Bundle, enteredId: String): Pair<String, String>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BundlesTest {
    private val base = Coords(37.5446, 127.0560)

    /** A place `northM` meters north and `eastM` meters east of the base. */
    private fun at(id: String, northM: Double, eastM: Double = 0.0, name: String = id, endDate: String? = null) = place(
        id = id, name = name, endDate = endDate,
        latitude = base.latitude + northM / 111_320.0,
        longitude = base.longitude + eastM / (111_320.0 * Math.cos(Math.toRadians(base.latitude))),
    )

    @Test
    fun `places link in a chain within 700m`() {
        val ps = listOf(at("a", 0.0), at("b", 600.0), at("c", 1200.0), at("far", 5000.0), at("far2", 5300.0))
        val bs = bundles(ps, NOW)
        assertEquals(1, bs.size)
        assertEquals(setOf("a", "b", "c"), bs.single().places.map { it.id }.toSet())
        assertEquals("a", bs.single().id)
    }

    @Test
    fun `700m links, 701m does not`() {
        assertEquals(1, bundles(listOf(at("a", 0.0), at("b", 699.9), at("c", 1399.8)), NOW).size)
        assertEquals(0, bundles(listOf(at("a", 0.0), at("b", 701.0), at("c", 1402.0)), NOW).size)
    }

    @Test
    fun `fewer than 3 places are not a bundle, ended pop-ups do not count`() {
        assertEquals(0, bundles(listOf(at("a", 0.0), at("b", 100.0)), NOW).size)
        assertEquals(0, bundles(listOf(at("a", 0.0), at("b", 100.0), at("c", 200.0, endDate = "2026-01-01")), NOW).size)
    }

    @Test
    fun `nearest bundle first, or the biggest without a position`() {
        val near = listOf(at("n1", 0.0), at("n2", 100.0), at("n3", 200.0))
        val far = listOf(at("f1", 9000.0), at("f2", 9100.0), at("f3", 9200.0), at("f4", 9300.0))
        assertEquals(listOf("n1", "f1"), bundles(near + far, NOW, base).map { it.id })
        assertEquals(listOf("f1", "n1"), bundles(near + far, NOW, null).map { it.id })
    }

    @Test
    fun `bundleOf returns null when the place is no longer in a bundle`() {
        val ps = listOf(at("a", 0.0), at("b", 100.0), at("c", 200.0))
        assertEquals("a", bundleOf(ps, "c", NOW)?.id)
        assertNull(bundleOf(ps.dropLast(1), "a", NOW))
        assertNull(bundleOf(ps, "gone", NOW))
    }

    @Test
    fun `region names lose their numbers`() {
        assertEquals("성수동", regionDisplayName("성수2가1동"))
        assertEquals("성수동", regionDisplayName("성수1가2동"))
        assertEquals("역삼동", regionDisplayName("역삼1동"))
        assertEquals("종로동", regionDisplayName("종로1·2·3·4가동"))
        assertEquals("연남동", regionDisplayName("연남동"))
        assertEquals("동", regionDisplayName("동"))
    }

    @Test
    fun `alert names the entered place first, then the nearest, no route`() {
        val b = bundles(listOf(at("a", 0.0, name = "어니언 성수"), at("b", 300.0, name = "무비랜드"), at("c", 100.0, name = "로우키"), at("d", 500.0, name = "대림창고")), NOW).single()
        val (title, body) = bundleAlertText("성수동", b, "a")
        assertEquals("📍 성수동에 가 볼 곳 4곳이 있어요", title)
        assertEquals("어니언 성수, 로우키 외 2곳", body)
    }

    @Test
    fun `fallback name uses the place nearest the center`() {
        val b = bundles(listOf(at("a", 0.0, name = "가"), at("b", 100.0, name = "나"), at("c", 200.0, name = "다")), NOW).single()
        assertEquals("나 근처", fallbackBundleName(b))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*BundlesTest*" --console=plain -q 2>&1 | grep "^e:" | head -3`
Expected: `Unresolved reference 'bundles'`.

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * Neighborhood bundles: saved places within walking distance of each other, recomputed from the
 * saved places every time (never stored). Single linkage: places 700m apart chain together.
 */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Place

const val BUNDLE_LINK_M = 700.0
const val BUNDLE_MIN = 3

/** `id` is the smallest member id, so it stays put while the members do. */
data class Bundle(val id: String, val places: List<Place>) {
    val center: Coords get() = Coords(places.map { it.latitude }.average(), places.map { it.longitude }.average())
}

fun bundles(places: List<Place>, now: Long, here: Coords? = null): List<Bundle> {
    val live = places.filterNot { placeEnded(it, now) }
    val parent = IntArray(live.size) { it }
    fun root(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        return r
    }
    for (i in live.indices) for (j in i + 1 until live.size) {
        val d = distanceMeters(Coords(live[i].latitude, live[i].longitude), Coords(live[j].latitude, live[j].longitude))
        if (d <= BUNDLE_LINK_M) parent[root(i)] = root(j)
    }
    val groups = live.indices.groupBy { root(it) }.values
        .filter { it.size >= BUNDLE_MIN }
        .map { idx -> idx.map { live[it] }.sortedBy { it.id } }
        .map { Bundle(it.first().id, it) }
    return if (here != null) groups.sortedBy { distanceMeters(here, it.center) }
    else groups.sortedWith(compareByDescending<Bundle> { it.places.size }.thenBy { it.id })
}

fun bundleOf(places: List<Place>, placeId: String, now: Long): Bundle? =
    bundles(places, now).firstOrNull { b -> b.places.any { it.id == placeId } }

private val NUMBERED = Regex("""\d+(·\d+)*가\d*동$|\d+동$""")

/** "성수2가1동" → "성수동": administrative dong names as people say them. */
fun regionDisplayName(raw: String): String {
    val name = raw.replace(NUMBERED, "동")
    return if (name == "동") raw else name
}

private fun Place.at() = Coords(latitude, longitude)

/** When Kakao gave no name: the place nearest the bundle's center. */
fun fallbackBundleName(b: Bundle): String = "${b.places.minBy { distanceMeters(it.at(), b.center) }.name} 근처"

/** No walking time or route here: those live on the home screen. */
fun bundleAlertText(name: String, b: Bundle, enteredId: String): Pair<String, String> {
    val entered = b.places.first { it.id == enteredId }
    val rest = b.places.filter { it.id != enteredId }.sortedBy { distanceMeters(entered.at(), it.at()) }
    val body = "${entered.name}, ${rest.first().name}" + if (b.places.size > 2) " 외 ${b.places.size - 2}곳" else ""
    return "📍 ${name}에 가 볼 곳 ${b.places.size}곳이 있어요" to body
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*BundlesTest*" --console=plain -q; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.core.BundlesTest.xml`
Expected: `tests="8" skipped="0" failures="0"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/core/Bundles.kt app/src/test/java/com/capstone/snapspot/core/BundlesTest.kt
git commit -m "feat(core): neighborhood bundles, region display names and bundle alert text"
```

---

### Task 3: Route benchmark and docs/route.md

**Files:**
- Modify: `app/src/test/java/com/capstone/snapspot/sim/SvgChart.kt` (log y axis, categorical palette)
- Create: `app/src/test/java/com/capstone/snapspot/sim/RouteBench.kt`
- Modify: `app/build.gradle.kts` (`-ProuteBench`, `-ProuteWrite`)
- Modify: `app/src/main/java/com/capstone/snapspot/core/Route.kt` (`EXACT_MAX` value from the results)
- Create (generated): `docs/route.md`, `docs/route/time.svg`, `docs/route/gap.svg`

**Interfaces:**
- Consumes: Task 1 functions (`Stop`, `stopsOf`, `exactRoute`, `heuristicRoute`, `bruteForceRoute`, `lowerBound`); `SimData`, `SIM_BRANDS` from `sim/FetchSimData.kt`; `sim/seoul.json` (`places` with `hood`, `lat`, `lng`; `branches` per brand key).
- Produces: `lineChart(..., logY: Boolean = false, palette: Palette = Palette.ORDINAL)` and `enum class Palette { ORDINAL, CATEGORICAL }` in `SvgChart.kt`.

- [ ] **Step 1: Extend `SvgChart.kt`**

Add after the `Series` class:

```kotlin
/** ORDINAL: one blue ramp (ordered series). CATEGORICAL: blue/orange/aqua (distinct series). Both validated with the dataviz script. */
enum class Palette { ORDINAL, CATEGORICAL }
```

Change the signature to `fun lineChart(title: String, xLabel: String, xs: List<String>, yLabel: String, series: List<Series>, percent: Boolean = true, logY: Boolean = false, palette: Palette = Palette.ORDINAL): String`.

In the `<style>` block, choose the series colors by palette:

```kotlin
        val (light, dark) = when (palette) {
            Palette.ORDINAL -> listOf("#86b6ef", "#2a78d6", "#104281") to listOf("#184f95", "#3987e5", "#9ec5f4")
            // Aqua sits at 2.74:1 on the light surface: every line carries a direct label and the doc has a table.
            Palette.CATEGORICAL -> listOf("#2a78d6", "#eb6834", "#1baf7a") to listOf("#3987e5", "#d95926", "#199e70")
        }
```

and build `.l0/.l1/.l2/.m0/.m1/.m2` from `light` and, inside the dark media query, from `dark`, replacing the hard-coded hex values.

For `logY`: compute the axis on `log10(v)`:

```kotlin
    fun tr(v: Double) = if (logY) Math.log10(v) else v
```

Use `tr` for `lo`, `hi` and `py` (`py(v) = TOP + plotH * (1 - (tr(v) - lo) / (hi - lo))`). With `logY`, `lo = floor(log10(min))`, `hi = ceil(log10(max))`, ticks at every integer power `p` in `lo..hi`, labeled by `10^p` formatted as `0.01`, `0.1`, `1`, `10`, `100`, `1,000` (use `String.format(Locale.US, "%,.${max(0, -p)}f", Math.pow(10.0, p.toDouble()))`). Values ≤ 0 are not allowed with `logY` (check and throw).

Labels: the end label uses `num(v, percent)`; for non-percent values ≥ 10 print no decimals (`"%.0f"`), otherwise two.

- [ ] **Step 2: Write `RouteBench.kt`**

```kotlin
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
private const val BRUTE_MAX = 9
private const val HELD_KARP_MAX = 16
private const val BUDGET_MS = 50.0

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

/** Median wall time in ms of `f` over the cases, after warming the JIT on the first one. */
private fun timeMs(cs: List<Case>, f: (Case) -> Unit): Double {
    repeat(3) { f(cs[0]) }
    val times = cs.map { c -> val t = System.nanoTime(); f(c); (System.nanoTime() - t) / 1e6 }.sorted()
    return times[times.size / 2]
}

class RouteBench {
    @Test
    fun bench() {
        assumeTrue(System.getProperty("route.run") == "true", "run with ./gradlew :app:testDebugUnitTest -ProuteBench")
        data class Row(val n: Int, val brute: Double?, val hk: Double?, val heur: Double, val gapNn: Double, val gap2: Double, val gapAll: Double, val vsMst: Boolean)
        val rows = NS.map { n ->
            val cs = cases(n)
            val brute = if (n <= BRUTE_MAX) timeMs(cs) { bruteForceRoute(it.stops, it.start) } else null
            val hk = if (n <= HELD_KARP_MAX) timeMs(cs) { exactRoute(it.stops, it.start) } else null
            val heur = timeMs(cs) { heuristicRoute(it.stops, it.start) }
            // Gap against the optimum when Held-Karp can give it (n ≤ 12), else against the MST lower bound (an upper bound on the gap).
            val vsMst = n > 12
            fun gap(f: (Case) -> Double) = cs.map { c ->
                val ref = if (vsMst) lowerBound(c.stops, c.start) else exactRoute(c.stops, c.start).meters
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
        val cutoff = rows.filter { it.hk != null && it.hk <= BUDGET_MS }.maxOf { it.n }
        fun ms(v: Double?) = v?.let { String.format(Locale.US, if (it < 1) "%.3f" else "%.1f", it) } ?: "–"
        fun pc(v: Double) = String.format(Locale.US, "%.1f%%", v)
        val table = buildString {
            appendLine("| 장소 수 n | 완전 탐색 (ms) | Held-Karp (ms) | 근사 (ms) | 가까운 곳부터 잇기 | + 2-opt | + Or-opt | 비교 기준 |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (r in rows) appendLine("| ${r.n} | ${ms(r.brute)} | ${ms(r.hk)} | ${ms(r.heur)} | ${pc(r.gapNn)} | ${pc(r.gap2)} | ${pc(r.gapAll)} | ${if (r.vsMst) "MST 하한" else "최적 (Held-Karp)"} |")
        }
        val timeChart = lineChart(
            title = "장소 수별 실행 시간 (중앙값, 로그 축)", xLabel = "장소 수 n", xs = NS.map { "$it" }, yLabel = "ms",
            series = listOf(
                Series("완전 탐색", 0, rows.filter { it.brute != null }.map { it.brute!! }),
                Series("Held-Karp", 1, rows.filter { it.hk != null }.map { it.hk!! }),
                Series("근사", 2, rows.map { it.heur }),
            ), percent = false, logY = true, palette = Palette.CATEGORICAL,
        )
        val gapChart = lineChart(
            title = "근사의 최적 대비 거리 차이 (n ≤ 12 최적, 그 위는 MST 하한 기준)", xLabel = "장소 수 n", xs = NS.map { "$it" }, yLabel = "%",
            series = listOf(
                Series("가까운 곳부터 잇기", 0, rows.map { it.gapNn }),
                Series("+ 2-opt", 1, rows.map { it.gap2 }),
                Series("+ Or-opt", 2, rows.map { it.gapAll }),
            ),
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
        println("cutoff (Held-Karp ≤ ${BUDGET_MS}ms): n = $cutoff; app uses EXACT_MAX = $EXACT_MAX")
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
| **가까운 곳부터 잇기 + 2-opt + Or-opt + 지점 다시 고르기** | 한 바퀴 O(g³ + Σkᵢkᵢ₊₁) (구현), 몇 바퀴 반복 | 근사 | ${EXACT_MAX + 1}곳 이상 |
| 최소 신장 트리(MST, Prim) | O(g²·k²) | 하한 | 근사의 오차 상한 계산 |

- **Held-Karp**: "지금까지 방문한 장소 집합(비트마스크)과 마지막 장소"를 상태로 두고, 각 상태까지의 최단 거리를 작은 집합부터 채워요. 출발점이 없으면 모든 장소를 출발 상태로 둬요.
- **2-opt**: 경로의 한 구간을 뒤집어 엇갈린 두 선을 풀어요. **Or-opt**: 1~3곳을 떼어 다른 자리에 끼워 넣어요. 둘 다 더 줄지 않을 때까지 반복해요.
- **MST 하한**: 최소 신장 트리는 어떤 열린 경로보다도 짧거나 같아서, 정답을 모르는 큰 n에서도 "근사가 최적보다 최대 몇 % 길다"를 보장할 수 있어요.

### 실험

- 데이터: 실제 서울 8개 동네의 장소 160곳(`sim/seoul.json`). 동네 하나에서 n곳을 무작위로 뽑고, 출발점은 그 장소들 중심 근처의 임의 위치. n마다 ${REPEATS}번.
- 시간: JVM 예열 후 중앙값. 완전 탐색은 n ≤ $BRUTE_MAX, Held-Karp는 n ≤ $HELD_KARP_MAX까지.
- 거리 차이: n ≤ 12는 Held-Karp 최적해와 비교한 평균. n > 12는 MST 하한과 비교한 값이라 실제 차이보다 큰 **상한**이에요.

![장소 수별 실행 시간](route/time.svg)
![근사의 최적 대비 거리 차이](route/gap.svg)

$table
### 쿠폰 쓸 곳 포함: 지점 고르기

"쿠폰 쓸 곳 포함"을 켜면 쿠폰 브랜드마다 근처 지점 중 **한 곳만** 들르면 돼요. 이건 무리마다 한 곳을 고르는 **일반화 외판원 문제(Generalized TSP)**예요. Held-Karp의 상태를 (들른 무리 집합, 마지막으로 들른 지점)으로 넓혀 O(2^g·N²)에 정확히 풀어요(g = 무리 수, N = 전체 후보 수).

비교: 브랜드마다 장소들 중심에서 가장 가까운 지점을 미리 고르는 **단순 방법** 대 **지점 선택 최적화**. 동네에서 장소 5~8곳과 실제 지점(장소에서 500m 안, 브랜드마다 최대 8곳)을 60번 뽑았어요.

$coupons
### 결론

- Held-Karp가 ${BUDGET_MS.toInt()}ms 안에 끝나는 가장 큰 n은 **$cutoff**이에요. 앱의 전환 기준 `EXACT_MAX`는 $EXACT_MAX이에요.
- 한 동네에 저장하는 장소는 보통 10곳 이하라, 대부분의 묶음은 정확한 최단 경로를 받아요.
"""
```

- [ ] **Step 3: Gradle switch** — in `app/build.gradle.kts`, inside `unitTests.all { ... }` after the `simFetch` block, add:

```kotlin
            // ./gradlew :app:testDebugUnitTest -ProuteBench [-ProuteWrite]: route algorithm benchmark (docs/route.md).
            if (project.hasProperty("routeBench")) {
                it.filter.includeTestsMatching("*RouteBench*")
                it.systemProperty("route.run", "true")
                it.systemProperty("route.write", project.hasProperty("routeWrite").toString())
                it.systemProperty("route.out", rootProject.file("docs/route.md").absolutePath)
                it.testLogging.showStandardStreams = true
                it.outputs.upToDateWhen { false }
            }
```

- [ ] **Step 4: Run the benchmark**

Run: `./gradlew :app:testDebugUnitTest -ProuteBench -ProuteWrite --console=plain > <workspace>/bench.log 2>&1; grep -E "cutoff|^e:|FAILED" <workspace>/bench.log`
Expected: a line `cutoff (Held-Karp ≤ 50.0ms): n = N; app uses EXACT_MAX = 10`, and `docs/route.md`, `docs/route/time.svg`, `docs/route/gap.svg` written. If the heuristic median at n = 20 exceeds 50ms, apply the delta-formula optimization noted in Task 1 and rerun Task 1's tests.

- [ ] **Step 5: Set `EXACT_MAX`** to the printed cutoff `N` (a whole number, capped at 16) in `core/Route.kt`, rerun the benchmark with `-ProuteWrite` so the doc states the final value, run `./gradlew :app:testDebugUnitTest --console=plain -q` (RouteTest uses `EXACT_MAX` symbolically), render both SVGs with headless Chrome (light and dark, as done for `docs/sim`) and look at them.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/com/capstone/snapspot/sim/SvgChart.kt app/src/test/java/com/capstone/snapspot/sim/RouteBench.kt app/build.gradle.kts app/src/main/java/com/capstone/snapspot/core/Route.kt docs/route.md docs/route
git commit -m "docs: route algorithm benchmark on real Seoul places; EXACT_MAX from it"
```

---

### Task 4: Region names from Kakao, cached

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/net/KakaoHttp.kt` (add `regionName`)
- Create: `app/src/main/java/com/capstone/snapspot/data/RegionNames.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt` (AppContainer: `val regionNames = RegionNames(dir)`)
- Test: `app/src/test/java/com/capstone/snapspot/net/KakaoHttpTest.kt`, `app/src/test/java/com/capstone/snapspot/data/RegionNamesTest.kt`

**Interfaces:**
- Consumes: `regionDisplayName` (Task 2), `cellOf(p: Coords): Cell` (`core/Branches.kt`, `Cell.key`), `JsonFile` (`data/Repository.kt`).
- Produces: `suspend fun KakaoHttp.regionName(at: Coords): String?` (raw H-dong name, null when none); `class RegionNames(dir: File)` with `val names: StateFlow<Map<String, String>>`, `fun cached(at: Coords): String?`, `suspend fun resolve(at: Coords, lookup: suspend (Coords) -> String?): String?`.

- [ ] **Step 1: Failing tests**

Append to `KakaoHttpTest`:

```kotlin
    @Test
    fun `region name is the administrative dong`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"documents":[{"region_type":"B","region_3depth_name":"성수동2가"},{"region_type":"H","region_3depth_name":"성수2가1동"}]}""",
            ),
        )
        assertEquals("성수2가1동", kakao.regionName(com.capstone.snapspot.core.Coords(37.5446, 127.056)))
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v2/local/geo/coord2regioncode.json"))
        assertTrue(req.path!!.contains("x=127.056") && req.path!!.contains("y=37.5446"))
    }
```

Create `RegionNamesTest`:

```kotlin
package com.capstone.snapspot.data

import com.capstone.snapspot.core.Coords
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RegionNamesTest {
    @TempDir
    lateinit var dir: File
    private val seongsu = Coords(37.5446, 127.0560)

    @Test
    fun `resolves once, stores the display name per cell, survives a restart`() = runTest {
        var calls = 0
        val names = RegionNames(dir)
        assertNull(names.cached(seongsu))
        assertEquals("성수동", names.resolve(seongsu) { calls++; "성수2가1동" })
        assertEquals("성수동", names.resolve(Coords(37.5447, 127.0561)) { calls++; "다른동" })
        assertEquals(1, calls)
        assertEquals("성수동", RegionNames(dir).cached(seongsu))
    }

    @Test
    fun `lookup failures are not cached`() = runTest {
        val names = RegionNames(dir)
        assertNull(names.resolve(seongsu) { throw java.io.IOException("offline") })
        assertNull(names.resolve(seongsu) { null })
        assertEquals("성수동", names.resolve(seongsu) { "성수1가1동" })
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*KakaoHttpTest*" --tests "*RegionNamesTest*" --console=plain -q 2>&1 | grep "^e:" | head -3`
Expected: `Unresolved reference 'regionName'` / `'RegionNames'`.

- [ ] **Step 3: Implement**

In `KakaoHttp.kt` add (the region endpoint lives under `/geo`, next to the `/search` base):

```kotlin
    @Serializable
    private data class Region(val region_type: String = "", val region_3depth_name: String = "")

    /** Administrative dong ("성수2가1동") at a point, or null. */
    suspend fun regionName(at: Coords): String? {
        if (key.isEmpty()) return null
        val url = "${base.substringBeforeLast("/search")}/geo/coord2regioncode.json".toHttpUrl().newBuilder()
            .addQueryParameter("x", at.longitude.toString())
            .addQueryParameter("y", at.latitude.toString())
            .build()
        val req = Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()
        client.newCall(req).await().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw KakaoException("카카오 행정동 조회 실패 (${res.code}): ${body.take(200)}")
            return json.decodeFromString(Page.serializer(Region.serializer()), body).documents
                .firstOrNull { it.region_type == "H" }?.region_3depth_name?.takeIf { it.isNotBlank() }
        }
    }
```

(import `com.capstone.snapspot.core.Coords`; add `@Suppress("PropertyName")` on `Region` like the other DTOs.)

Create `data/RegionNames.kt`:

```kotlin
package com.capstone.snapspot.data

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.cellOf
import com.capstone.snapspot.core.regionDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Neighborhood display names per ~1km grid cell (region-names.json), so bundle alerts can name the
 * neighborhood without a network call. Not part of the team's data contract.
 */
class RegionNames(dir: File) {
    private val lock = Mutex()
    private val file = JsonFile(File(dir, "region-names.json"), MapSerializer(String.serializer(), String.serializer()))
    private val _names = MutableStateFlow(file.readNow().orEmpty())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    fun cached(at: Coords): String? = _names.value[cellOf(at).key]

    /** Cached name, or ask `lookup` (raw Kakao name) once and store the display name. Failures are not cached. */
    suspend fun resolve(at: Coords, lookup: suspend (Coords) -> String?): String? {
        cached(at)?.let { return it }
        val raw = try {
            lookup(at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val name = regionDisplayName(raw)
        lock.withLock {
            val next = file.read().orEmpty() + (cellOf(at).key to name)
            file.write(next)
            _names.value = next
        }
        return name
    }
}
```

In `AppContainer` add after `val pending = PendingStore(dir)`: `val regionNames = RegionNames(dir)` (import `com.capstone.snapspot.data.RegionNames`).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*KakaoHttpTest*" --tests "*RegionNamesTest*" --console=plain -q 2>&1 | grep -E "^e:|FAILED"; grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.net.KakaoHttpTest.xml app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.data.RegionNamesTest.xml`
Expected: no `e:`/FAILED; both files report `failures="0"`, RegionNamesTest `tests="2"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/net/KakaoHttp.kt app/src/main/java/com/capstone/snapspot/data/RegionNames.kt app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt app/src/test/java/com/capstone/snapspot/net/KakaoHttpTest.kt app/src/test/java/com/capstone/snapspot/data/RegionNamesTest.kt
git commit -m "feat: neighborhood names from Kakao coord2regioncode, cached per cell"
```

---

### Task 5: Bundle alerts

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/notify/AlertService.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt` (pass `bundleName`)
- Test: `app/src/test/java/com/capstone/snapspot/notify/AlertServiceTest.kt`

**Interfaces:**
- Consumes: `bundleOf`, `bundleAlertText`, `fallbackBundleName`, `Bundle` (Task 2); `RegionNames.cached` (Task 4); `PLACE_COOLDOWN_MS`, `suppressReason` (`core/Alerts.kt`); `repo.markPlaceNotified(id, at)`.
- Produces: `AlertService(repo, notifier, onScreen = { false }, bundleName: (Bundle) -> String = ::fallbackBundleName, clock = System::currentTimeMillis)`; bundle deep link `snapspot://bundle/{placeId}`.

- [ ] **Step 1: Failing tests** (append to `AlertServiceTest`)

```kotlin
    private suspend fun seongsuBundle(repo: Repository, lastNotifiedC: Long? = null) {
        repo.upsertPlace(place(id = "a", name = "어니언 성수", latitude = 37.5446, longitude = 127.0560))
        repo.upsertPlace(place(id = "b", name = "로우키", latitude = 37.5450, longitude = 127.0565))
        repo.upsertPlace(place(id = "c", name = "무비랜드", latitude = 37.5455, longitude = 127.0570, lastNotifiedAt = lastNotifiedC))
        repo.upsertPlace(place(id = "z", name = "멀리", latitude = 37.60, longitude = 127.10))
    }

    @Test
    fun `entering a bundle place sends one bundle alert and quiets the others`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo)
        assertTrue(svc.onPlaceEnter("a"))
        assertEquals(Triple("📍 성수동에 가 볼 곳 3곳이 있어요", "어니언 성수, 로우키 외 1곳", "snapspot://bundle/a"), notifier.shown.single())
        assertTrue(repo.places.value.filter { it.id in setOf("a", "b", "c") }.all { it.lastNotifiedAt == NOW })
        assertFalse(svc.onPlaceEnter("b"))
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun `a member alerted recently holds the bundle alert back`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo, lastNotifiedC = NOW - 60_000)
        assertFalse(svc.onPlaceEnter("a"))
        assertEquals(0, notifier.shown.size)
    }

    @Test
    fun `a place outside any bundle still gets its own alert`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo)
        assertTrue(svc.onPlaceEnter("z"))
        assertEquals("snapspot://place/z", notifier.shown.single().third)
    }

    @Test
    fun `bundle alerts stay quiet on screen`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, onScreen = { true }, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo)
        assertFalse(svc.onPlaceEnter("a"))
        assertEquals(0, notifier.shown.size)
    }
```

If `place(...)` in `core/AlertsTest.kt` lacks `lastNotifiedAt` (it has it) or `name`/`latitude`/`longitude` (it has them), use them as named above.

- [ ] **Step 2: Run, expect failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlertServiceTest*" --console=plain -q 2>&1 | grep "^e:" | head -2`
Expected: `No parameter with name 'bundleName' found`.

- [ ] **Step 3: Implement** in `AlertService.kt`:

Constructor (new parameter before `clock` so trailing-lambda callers still bind `clock`):

```kotlin
    private val onScreen: () -> Boolean = { false },
    /** Display name of a bundle's neighborhood; must not block (cache only). */
    private val bundleName: (Bundle) -> String = ::fallbackBundleName,
    private val clock: () -> Long = System::currentTimeMillis,
```

Replace the body of `onPlaceEnter` with:

```kotlin
    suspend fun onPlaceEnter(placeId: String, force: Boolean = false): Boolean = lock.withLock {
        if (!force && onScreen()) return@withLock false
        val places = repo.places.value
        val place = places.firstOrNull { it.id == placeId } ?: return@withLock false
        val now = clock()
        val bundle = bundleOf(places, placeId, now)
        if (bundle != null && !force) {
            // One alert per neighborhood walk: any member alerted recently holds it back.
            if (bundle.places.any { p -> p.lastNotifiedAt?.let { now - it < PLACE_COOLDOWN_MS } == true }) return@withLock false
            val (title, body) = bundleAlertText(bundleName(bundle), bundle, placeId)
            notifier.show(bundle.id.hashCode(), title, body, "snapspot://bundle/$placeId")
            for (p in bundle.places) repo.markPlaceNotified(p.id, now)
            return@withLock true
        }
        if (!force && suppressReason(place, now) != null) return@withLock false
        val (title, body) = placeAlertText(place)
        notifier.show(place.id.hashCode(), title, body, "snapspot://place/${place.id}")
        repo.markPlaceNotified(place.id, now)
        true
    }
```

(`force` — the place screen's test button — keeps sending the single-place alert. Imports: `Bundle`, `bundleOf`, `bundleAlertText`, `fallbackBundleName`, `PLACE_COOLDOWN_MS`.)

In `AppContainer`, `regionNames` must be declared before `alerts`; change `alerts` to:

```kotlin
    val alerts = AlertService(repo, AndroidNotifier(ctx), onScreen = { onScreen }, bundleName = { b -> regionNames.cached(b.center) ?: fallbackBundleName(b) })
```

(import `com.capstone.snapspot.core.fallbackBundleName`).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q 2>&1 | grep -E "^e:|FAILED" | head; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.notify.AlertServiceTest.xml`
Expected: no output from the first grep; `failures="0"`, 4 more tests than before.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/notify/AlertService.kt app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt app/src/test/java/com/capstone/snapspot/notify/AlertServiceTest.kt
git commit -m "feat(notify): one alert per neighborhood bundle"
```

---

### Task 6: Home screen bundles and the bundle deep link

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/ui/map/SpotMap.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/ui/Nav.kt`

**Interfaces:**
- Consumes: `bundles`, `Bundle`, `fallbackBundleName` (Task 2); `shortestRoute`, `stopsOf`, `Stop`, `RouteResult`, `walkMinutes` (Task 1); `RegionNames.names/resolve`, `KakaoHttp.regionName` (Task 4); `brandGroups(coupons, now)` (`core/Planner.kt`), `nearbyBranches(brand, here, api, cache, now)` (`core/Branches.kt`), `Branch` (`core/model/Models.kt`).
- Produces: `data class CouponStop(val brand: String, val branches: List<Branch>)`; `data class BundleItem(val bundle: Bundle, val name: String, val coupons: List<CouponStop>, val route: RouteResult, val start: Coords?, val minutes: Int, val couponsFailed: Boolean)` (route stops = the bundle's places first, then one stop per coupon brand); `HomeViewModel.withCoupons: StateFlow<Set<String>>`, `fun toggleCoupons(bundleId: String)`; `HomeViewModel.bundles: StateFlow<List<BundleItem>>`, `openBundle: StateFlow<String?>` (bundle id), `fun toggleBundle(id: String)`, `fun openBundleFor(placeId: String)`; `MapPlace.number: Int?`; `SpotMap(route: List<Coords> = emptyList())`.

This task is Compose/Android glue; it is verified by build and on the emulator in Task 7.

- [ ] **Step 1: View model** — in `HomeViewModel.kt` add:

```kotlin
data class CouponStop(val brand: String, val branches: List<Branch>)

/** Route stops: the bundle's places in order, then one stop per coupon brand (any one of its branches). */
data class BundleItem(val bundle: Bundle, val name: String, val coupons: List<CouponStop>, val route: RouteResult, val start: Coords?, val minutes: Int, val couponsFailed: Boolean)

    /** Start from the user only when they are in the neighborhood (within 1km of a member). */
    private val _openBundle = MutableStateFlow<String?>(null)
    val openBundle: StateFlow<String?> = _openBundle.asStateFlow()

    /** Bundles whose "쿠폰 쓸 곳 포함" switch is on, and the branches found for them (null = the fetch failed). */
    private val _withCoupons = MutableStateFlow<Set<String>>(emptySet())
    val withCoupons: StateFlow<Set<String>> = _withCoupons.asStateFlow()
    private val couponStops = MutableStateFlow<Map<String, List<CouponStop>?>>(emptyMap())

    val bundles: StateFlow<List<BundleItem>> = combine(c.repo.places, here, c.regionNames.names, _withCoupons, couponStops) { list, at, _, on, found ->
        bundles(list, System.currentTimeMillis(), at).map { b ->
            val start = at?.takeIf { a -> b.places.any { distanceMeters(a, Coords(it.latitude, it.longitude)) <= 1000 } }
            val coupons = if (b.id in on) found[b.id].orEmpty() else emptyList()
            val stops: List<Stop> = stopsOf(b.places.map { Coords(it.latitude, it.longitude) }) +
                coupons.map { cs -> cs.branches.map { Coords(it.latitude, it.longitude) } }
            val route = shortestRoute(stops, start)
            val failed = b.id in on && found.containsKey(b.id) && found[b.id] == null
            BundleItem(b, c.regionNames.cached(b.center) ?: fallbackBundleName(b), coupons, route, start, walkMinutes(route.meters), failed)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Coupon stops on/off for a bundle; on fetches each brand's branches within 500m of its places (nearest 8 to the center). */
    fun toggleCoupons(bundleId: String) {
        val on = bundleId !in _withCoupons.value
        _withCoupons.value = if (on) _withCoupons.value + bundleId else _withCoupons.value - bundleId
        if (!on) return
        val b = bundles.value.firstOrNull { it.bundle.id == bundleId }?.bundle ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val found = runCatching {
                brandGroups(c.repo.coupons.value, now).mapNotNull { g ->
                    val near = nearbyBranches(g.brand, b.center, c.kakao, c.branchCache, now).branches
                        .filter { br -> b.places.any { distanceMeters(Coords(it.latitude, it.longitude), Coords(br.latitude, br.longitude)) <= 500 } }
                        .sortedBy { distanceMeters(b.center, Coords(it.latitude, it.longitude)) }
                        .take(8)
                    if (near.isEmpty()) null else CouponStop(g.brand, near)
                }
            }.getOrNull()
            couponStops.value = couponStops.value + (bundleId to found)
        }
    }

    fun toggleBundle(id: String) {
        _openBundle.value = if (_openBundle.value == id) null else id
        val item = bundles.value.firstOrNull { it.bundle.id == id } ?: return
        if (_openBundle.value == id) {
            val span = item.bundle.places.maxOf { distanceMeters(item.bundle.center, Coords(it.latitude, it.longitude)) }
            _focus.value = MapFocus(item.bundle.center, System.nanoTime(), if (span < 400) 16 else if (span < 900) 15 else 14)
        }
    }

    /** From a bundle notification: open the bundle the place is in, or just the place if the bundle is gone. */
    fun openBundleFor(placeId: String) {
        val item = bundles.value.firstOrNull { b -> b.bundle.places.any { it.id == placeId } }
        if (item == null) focusOn(placeId) else if (_openBundle.value != item.bundle.id) toggleBundle(item.bundle.id)
    }
```

and in `init`, resolve names the cache lacks:

```kotlin
        // Name bundles the first time they show up (the alert path only reads this cache).
        viewModelScope.launch {
            bundles.collect { items ->
                for (i in items) if (c.regionNames.cached(i.bundle.center) == null) c.regionNames.resolve(i.bundle.center) { c.kakao.regionName(it) }
            }
        }
```

Imports: `Bundle`, `bundles`, `fallbackBundleName`, `RouteResult`, `shortestRoute`, `walkMinutes`, `MapFocus` (already), `combine` (already).

- [ ] **Step 2: Map** — in `SpotMap.kt`:
  - `data class MapPlace(..., val selected: Boolean, val number: Int? = null)`
  - `SpotMap(..., route: List<Coords> = emptyList(), ...)`; include `route` in the `LaunchedEffect(map, places, branches, here, refresh, route)` keys and pass it to `draw`.
  - In `draw`, after the place circles and before labels:

```kotlin
    if (route.size >= 2) {
        shapes.addPolyline(PolylineOptions.from(MapPoints.fromLatLng(route.map { latLng(it) }), 3f * density, SnapColors.ink.toArgb()))
    }
```

  - Place badge text: `p.number?.toString() ?: p.initial`.
  - Imports: `com.kakao.vectormap.shape.PolylineOptions`.

- [ ] **Step 3: Home screen** — in `HomeScreen.kt`:
  - Collect `val bundleItems by vm.bundles.collectAsState()`, `val openBundle by vm.openBundle.collectAsState()` and `val couponsOn by vm.withCoupons.collectAsState()`; `val open = bundleItems.firstOrNull { it.bundle.id == openBundle }`.
  - Route stops of an open bundle: for each `i` in `open.route.order.indices`, stop `s = open.route.order[i]` and pick `k = open.route.picks[i]`; it is a place when `s < open.bundle.places.size` (`open.bundle.places[s]`), otherwise coupon brand `open.coupons[s - open.bundle.places.size]` at `branches[k]`. Build `routePoints` in that order.
  - Map inputs: when `open != null`, give each place its route position (`number = i + 1`), add each picked coupon branch as an extra `MapPlace(id = "route-$i", at = branch coords, radiusM = 0.0, initial = brand.take(1), ended = false, selected = false, number = i + 1)` (skip drawing the radius circle when `radiusM == 0.0`), and pass `route = listOfNotNull(open.start) + routePoints`.
  - In the `Tab.PLACES` branch, before the places items:

```kotlin
                        if (bundleItems.isNotEmpty()) item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(bundleItems, key = { it.bundle.id }) { b ->
                                    BundleCard(b, open = b.bundle.id == openBundle, withCoupons = b.bundle.id in couponsOn, onClick = { vm.toggleBundle(b.bundle.id) }, onCoupons = { vm.toggleCoupons(b.bundle.id) })
                                }
                            }
                        }
                        // Open bundle: its places in route order; coupon stops follow as one-line rows.
                        val shown = if (open != null) open.route.order.filter { it < open.bundle.places.size }.map { open.bundle.places[it] } else places
```

    and iterate `items(shown, key = { it.id })` instead of `places`, passing `number = open?.let { o -> o.route.order.indexOfFirst { it < o.bundle.places.size && o.bundle.places[it].id == p.id } + 1 }` to `PlaceRow`; then, for each coupon stop in the route, an item with the text `"${i + 1}. ${branch.name} (쿠폰)"` in `SnapType.meta` (add an optional `number: Int? = null` parameter that, when set, shows a small numbered circle before the name).
  - Add:

```kotlin
@Composable
private fun BundleCard(b: BundleItem, open: Boolean, withCoupons: Boolean, onClick: () -> Unit, onCoupons: () -> Unit) {
    Column(
        Modifier.width(200.dp).clip(RoundedCornerShape(16.dp))
            .background(if (open) SnapColors.ink else SnapColors.surface)
            .border(1.dp, SnapColors.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val fg = if (open) SnapColors.surface else SnapColors.ink
        Text(b.name, style = SnapType.title, color = fg, maxLines = 1)
        Text("가 볼 곳 ${b.bundle.places.size}곳 · 걸어서 약 ${b.minutes}분", style = SnapType.meta, color = if (open) SnapColors.surface else SnapColors.muted)
        if (open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("쿠폰 쓸 곳 포함", style = SnapType.meta, color = fg, modifier = Modifier.weight(1f))
                Switch(checked = withCoupons, onCheckedChange = { onCoupons() })
            }
            if (b.couponsFailed) Text("쿠폰 지점을 불러오지 못했어요", style = SnapType.meta, color = SnapColors.surface)
        }
    }
}
```

  (imports as needed: `LazyRow`, `items` for LazyRow, `RoundedCornerShape`, `border`, `width`.)

- [ ] **Step 4: Deep link** — in `Nav.kt`'s `LaunchedEffect(deepLink)` `when (uri.host)`, add:

```kotlin
            "bundle" -> {
                nav.popBackStack("home", inclusive = false)
                if (id != null) home.openBundleFor(id)
            }
```

- [ ] **Step 5: Build and run all tests**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e:|FAILED" | head`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/home/HomeViewModel.kt app/src/main/java/com/capstone/snapspot/ui/home/HomeScreen.kt app/src/main/java/com/capstone/snapspot/ui/map/SpotMap.kt app/src/main/java/com/capstone/snapspot/ui/Nav.kt
git commit -m "feat(ui): neighborhood bundle cards with the shortest walking route"
```

---

### Task 7: Emulator check and README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Seed test places.** On `SnapSpot_API34` (Google Maps open for GPS), add three 성수 places next to the existing 어니언 성수 by pushing a `places.json` through `/data/local/tmp` + `run-as cp` (as done for the walking-alerts check), e.g. 로우키 (37.5450, 127.0565), 무비랜드 (37.5455, 127.0570), 대림창고 (37.5420, 127.0565), all `lastNotifiedAt: null`.

- [ ] **Step 2: Home.** Open the app at 성수. Expected: a card "성수동 · 가 볼 곳 4곳 · 걸어서 약 N분" (name "… 근처" first if the Kakao lookup has not returned yet, then 성수동). Tap it: numbered pins 1–4, an ink line through them from the user's position, the list in route order. Tap again: back to normal. Screenshot both states for the README.

- [ ] **Step 3: Alert.** Clear cooldowns, set the position 1km away, HOME + `am kill`, walk into 어니언 성수 at walking speed. Expected: one notification "📍 성수동에 가 볼 곳 4곳이 있어요 / 어니언 성수, 로우키 외 2곳" and no further alert while walking to 로우키. Tap it: home opens with the 성수동 card expanded.

- [ ] **Step 4: README.** Under "## 화면", add a bullet: `**동네 묶음**: 서로 700m 안에 저장한 장소가 3곳 이상이면 "성수동 · 가 볼 곳 4곳" 카드로 묶어요. 카드를 누르면 모두 들르는 최단 걷기 동선을 지도에 번호로 그려요. 그 동네에 들어가면 장소마다 울리는 대신 "성수동에 가 볼 곳 4곳이 있어요" 알림이 한 번 와요.` Add a short "### 최단 경로" subsection after "### 시뮬레이션 결과" that links `docs/route.md`, embeds `docs/route/time.svg`, and states the chosen `EXACT_MAX` and the measured heuristic gap at the largest exact n. Add the two screenshots to `docs/screenshots/` (`bundle-card.png`, `bundle-route.png`) and the README image row.

- [ ] **Step 5: Full check and commit**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e:|FAILED" | head`
Expected: no output.

```bash
git add README.md docs/screenshots/bundle-card.png docs/screenshots/bundle-route.png
git commit -m "docs: README for neighborhood bundles and the route benchmark"
```
