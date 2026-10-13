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

---

### Task 1: Route algorithms

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/core/Route.kt`
- Test: `app/src/test/java/com/capstone/snapspot/core/RouteTest.kt`

**Interfaces:**
- Consumes: `Coords`, `distanceMeters(a: Coords, b: Coords): Double` (`core/Distance.kt`).
- Produces:
  - `data class RouteResult(val order: List<Int>, val meters: Double)` — `order` indexes into the input `points`.
  - `fun bruteForceRoute(points: List<Coords>, start: Coords?): RouteResult`
  - `fun exactRoute(points: List<Coords>, start: Coords?): RouteResult` (Held-Karp)
  - `fun heuristicRoute(points: List<Coords>, start: Coords?, twoOpt: Boolean = true, orOpt: Boolean = true): RouteResult`
  - `fun mstLowerBound(points: List<Coords>, start: Coords?): Double`
  - `fun shortestRoute(points: List<Coords>, start: Coords?): RouteResult`
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

    /** `n` random points within ~600m of 성수, reproducible. */
    private fun points(n: Int, seed: Int): List<Coords> {
        val r = Random(seed)
        return List(n) { Coords(origin.latitude + (r.nextDouble() - 0.5) * 0.011, origin.longitude + (r.nextDouble() - 0.5) * 0.014) }
    }

    private fun length(points: List<Coords>, start: Coords?, order: List<Int>): Double {
        val path = listOfNotNull(start) + order.map { points[it] }
        return path.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
    }

    @Test
    fun `held-karp matches brute force`() {
        for (seed in 1..25) {
            val n = 3 + seed % 6
            val pts = points(n, seed)
            val start = if (seed % 2 == 0) Coords(origin.latitude + 0.003, origin.longitude) else null
            val exact = exactRoute(pts, start)
            val brute = bruteForceRoute(pts, start)
            assertEquals(brute.meters, exact.meters, 1e-6, "seed $seed")
            assertEquals(exact.meters, length(pts, start, exact.order), 1e-6)
            assertEquals((0 until n).toSet(), exact.order.toSet())
        }
    }

    @Test
    fun `heuristic is never shorter than exact and never below the MST bound`() {
        for (seed in 1..25) {
            val pts = points(4 + seed % 9, seed)
            val start = if (seed % 3 == 0) null else origin
            val exact = exactRoute(pts, start).meters
            val h = heuristicRoute(pts, start)
            assertTrue(h.meters >= exact - 1e-6, "seed $seed")
            assertTrue(mstLowerBound(pts, start) <= exact + 1e-6, "seed $seed")
            assertEquals(h.meters, length(pts, start, h.order), 1e-6)
        }
    }

    @Test
    fun `2-opt and Or-opt never make the route longer`() {
        for (seed in 1..25) {
            val pts = points(12 + seed % 9, seed)
            val nn = heuristicRoute(pts, origin, twoOpt = false, orOpt = false).meters
            val two = heuristicRoute(pts, origin, twoOpt = true, orOpt = false).meters
            val both = heuristicRoute(pts, origin).meters
            // Each local search only ever accepts shorter routes; the two may stop at different local optima.
            assertTrue(two <= nn + 1e-6 && both <= nn + 1e-6, "seed $seed")
        }
    }

    @Test
    fun `free start is never worse than any fixed first place`() {
        val pts = points(7, 3)
        val free = exactRoute(pts, null).meters
        for (i in pts.indices) assertTrue(free <= exactRoute(pts.filterIndexed { j, _ -> j != i }, pts[i]).meters + 1e-6)
    }

    @Test
    fun `small inputs`() {
        assertEquals(RouteResult(emptyList(), 0.0), shortestRoute(emptyList(), origin))
        assertEquals(listOf(0), shortestRoute(listOf(origin), null).order)
        assertEquals(0.0, shortestRoute(listOf(origin), null).meters)
        val two = shortestRoute(points(2, 9), null)
        assertEquals(2, two.order.size)
    }

    @Test
    fun `duplicate points are fine`() {
        val pts = listOf(origin, origin, Coords(origin.latitude + 0.002, origin.longitude), origin)
        val r = exactRoute(pts, null)
        assertEquals(4, r.order.size)
        assertEquals(distanceMeters(origin, pts[2]), r.meters, 1e-6)
        assertEquals(r.meters, heuristicRoute(pts, null).meters, 1e-6)
    }

    @Test
    fun `shortest route switches to the heuristic above EXACT_MAX`() {
        val big = points(EXACT_MAX + 5, 11)
        assertEquals(heuristicRoute(big, origin).meters, shortestRoute(big, origin).meters, 1e-6)
        val small = points(EXACT_MAX, 11)
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
Expected: `Unresolved reference 'exactRoute'` (and the other new names).

- [ ] **Step 3: Write the implementation**

```kotlin
/**
 * Shortest walking order through a neighborhood's saved places: an open-path traveling salesman
 * problem (start somewhere, visit every place once, no return). Straight-line distances.
 *
 * - [bruteForceRoute]: every order, O(n!). Ground truth for tests and the benchmark only.
 * - [exactRoute]: Held-Karp dynamic programming over (visited set, last place), O(n²·2ⁿ) time,
 *   O(n·2ⁿ) memory. Exact.
 * - [heuristicRoute]: nearest neighbor, then 2-opt (reverse a stretch) and Or-opt (move 1–3 places)
 *   until nothing improves. Roughly O(n²) per pass, not guaranteed optimal.
 * - [mstLowerBound]: a minimum spanning tree is never longer than any open path through the same
 *   points, so it bounds how far from optimal a heuristic route can be without knowing the optimum.
 *
 * [EXACT_MAX] (picked by RouteBench, see docs/route.md) decides which one the app uses.
 */
package com.capstone.snapspot.core

import kotlin.math.ceil

/** Up to this many places the app solves exactly; above it, the heuristic. */
const val EXACT_MAX = 10

/** Streets are not straight: walked distance ≈ straight distance × this. */
const val DETOUR_FACTOR = 1.3
const val WALK_M_PER_MIN = 70.0

/** `order` indexes into the input points; `meters` includes the leg from the start, if any. */
data class RouteResult(val order: List<Int>, val meters: Double)

fun walkMinutes(meters: Double): Int = ceil(meters * DETOUR_FACTOR / WALK_M_PER_MIN).toInt()

/** Distances between points; index n is the start when there is one. */
private class Dist(points: List<Coords>, start: Coords?) {
    val n = points.size
    private val all = points + listOfNotNull(start)
    private val d = Array(all.size) { i -> DoubleArray(all.size) { j -> distanceMeters(all[i], all[j]) } }
    val hasStart = start != null
    operator fun get(i: Int, j: Int) = d[i][j]
    /** Cost of reaching `first` from the start (0 without a start). */
    fun lead(first: Int) = if (hasStart) d[n][first] else 0.0
    fun length(order: List<Int>): Double =
        if (order.isEmpty()) 0.0 else lead(order[0]) + order.zipWithNext().sumOf { (a, b) -> d[a][b] }
}

fun bruteForceRoute(points: List<Coords>, start: Coords?): RouteResult {
    val dist = Dist(points, start)
    var best: List<Int> = emptyList()
    var bestLen = Double.MAX_VALUE
    fun permute(prefix: MutableList<Int>, left: MutableSet<Int>, len: Double) {
        if (len >= bestLen) return
        if (left.isEmpty()) {
            best = prefix.toList()
            bestLen = len
            return
        }
        for (next in left.toList()) {
            val add = if (prefix.isEmpty()) dist.lead(next) else dist[prefix.last(), next]
            prefix += next
            left -= next
            permute(prefix, left, len + add)
            left += next
            prefix.removeAt(prefix.size - 1)
        }
    }
    if (points.isEmpty()) return RouteResult(emptyList(), 0.0)
    permute(mutableListOf(), points.indices.toMutableSet(), 0.0)
    return RouteResult(best, bestLen)
}

fun exactRoute(points: List<Coords>, start: Coords?): RouteResult {
    val n = points.size
    if (n == 0) return RouteResult(emptyList(), 0.0)
    val dist = Dist(points, start)
    val full = (1 shl n) - 1
    // cost[mask * n + last]: shortest path visiting exactly `mask`, ending at `last`.
    val cost = DoubleArray((1 shl n) * n) { Double.MAX_VALUE }
    val prev = IntArray((1 shl n) * n) { -1 }
    for (i in 0 until n) cost[(1 shl i) * n + i] = dist.lead(i)
    for (mask in 1..full) {
        for (last in 0 until n) {
            if (mask and (1 shl last) == 0) continue
            val here = cost[mask * n + last]
            if (here == Double.MAX_VALUE) continue
            for (next in 0 until n) {
                if (mask and (1 shl next) != 0) continue
                val m2 = mask or (1 shl next)
                val c = here + dist[last, next]
                if (c < cost[m2 * n + next]) {
                    cost[m2 * n + next] = c
                    prev[m2 * n + next] = last
                }
            }
        }
    }
    var last = (0 until n).minBy { cost[full * n + it] }
    val len = cost[full * n + last]
    val order = ArrayDeque<Int>()
    var mask = full
    while (last >= 0) {
        order.addFirst(last)
        val p = prev[mask * n + last]
        mask = mask xor (1 shl last)
        last = p
    }
    return RouteResult(order.toList(), len)
}

fun heuristicRoute(points: List<Coords>, start: Coords?, twoOpt: Boolean = true, orOpt: Boolean = true): RouteResult {
    val n = points.size
    if (n == 0) return RouteResult(emptyList(), 0.0)
    val dist = Dist(points, start)
    // With a start, one run from it; without, every place as the first one and keep the best.
    val firsts = if (start != null) listOf(-1) else (0 until n).toList()
    var best: List<Int> = emptyList()
    var bestLen = Double.MAX_VALUE
    for (first in firsts) {
        val order = nearestNeighbor(dist, first)
        improve(dist, order, twoOpt, orOpt)
        val len = dist.length(order)
        if (len < bestLen) {
            best = order.toList()
            bestLen = len
        }
    }
    return RouteResult(best, bestLen)
}

/** `first` = -1: start from the start point. */
private fun nearestNeighbor(dist: Dist, first: Int): MutableList<Int> {
    val left = (0 until dist.n).toMutableSet()
    val order = mutableListOf<Int>()
    if (first >= 0) {
        order += first
        left -= first
    }
    while (left.isNotEmpty()) {
        val next = if (order.isEmpty()) left.minBy { dist.lead(it) } else left.minBy { dist[order.last(), it] }
        order += next
        left -= next
    }
    return order
}

/** Applies 2-opt and Or-opt moves in place until neither shortens the route. */
private fun improve(dist: Dist, order: MutableList<Int>, twoOpt: Boolean, orOpt: Boolean) {
    // The first place is free to change only with a start point; without one, every run fixes its own first.
    val lo = if (dist.hasStart) 0 else 1
    var improved = true
    while (improved) {
        improved = false
        if (twoOpt) {
            for (i in lo until order.size - 1) for (k in i + 1 until order.size) {
                val before = dist.length(order)
                order.subList(i, k + 1).reverse()
                if (dist.length(order) < before - 1e-9) improved = true else order.subList(i, k + 1).reverse()
            }
        }
        if (orOpt) {
            for (len in 1..3) {
                var i = lo
                while (i + len <= order.size) {
                    val before = dist.length(order)
                    val seg = order.subList(i, i + len).toList()
                    repeat(len) { order.removeAt(i) }
                    var bestPos = -1
                    var bestLen = before - 1e-9
                    for (pos in lo..order.size) {
                        order.addAll(pos, seg)
                        val l = dist.length(order)
                        if (l < bestLen) {
                            bestLen = l
                            bestPos = pos
                        }
                        repeat(len) { order.removeAt(pos) }
                    }
                    if (bestPos >= 0) {
                        order.addAll(bestPos, seg)
                        improved = true
                    } else {
                        order.addAll(i, seg)
                    }
                    i++
                }
            }
        }
    }
}

/** Prim's minimum spanning tree over the points (and the start). */
fun mstLowerBound(points: List<Coords>, start: Coords?): Double {
    val all = points + listOfNotNull(start)
    if (all.size < 2) return 0.0
    val inTree = BooleanArray(all.size)
    val best = DoubleArray(all.size) { Double.MAX_VALUE }
    best[0] = 0.0
    var total = 0.0
    repeat(all.size) {
        val u = all.indices.filter { !inTree[it] }.minBy { best[it] }
        inTree[u] = true
        total += best[u]
        for (v in all.indices) if (!inTree[v]) best[v] = minOf(best[v], distanceMeters(all[u], all[v]))
    }
    return total
}

fun shortestRoute(points: List<Coords>, start: Coords?): RouteResult =
    if (points.size <= EXACT_MAX) exactRoute(points, start) else heuristicRoute(points, start)
```

Note on `improve`: `dist.length(order)` is O(n) per move, which makes a pass O(n³). It is simple and correct; Task 3's benchmark measures it. If the benchmark shows the heuristic over 50ms at n = 20, replace the length recomputation with the usual O(1) delta formulas and keep these tests.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*RouteTest*" --console=plain -q; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.core.RouteTest.xml`
Expected: `tests="8" skipped="0" failures="0"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/core/Route.kt app/src/test/java/com/capstone/snapspot/core/RouteTest.kt
git commit -m "feat(core): shortest walking route (Held-Karp, 2-opt/Or-opt, MST bound)"
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
- Consumes: Task 1 functions; `SimData`, `SIM_BRANDS` not needed; `sim/seoul.json` via the same loader as `SimulateWindow` (`places` with `hood`, `lat`, `lng`).
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
import com.capstone.snapspot.core.mstLowerBound
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

private class Case(val points: List<Coords>, val start: Coords)

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
            val brute = if (n <= BRUTE_MAX) timeMs(cs) { bruteForceRoute(it.points, it.start) } else null
            val hk = if (n <= HELD_KARP_MAX) timeMs(cs) { exactRoute(it.points, it.start) } else null
            val heur = timeMs(cs) { heuristicRoute(it.points, it.start) }
            // Gap against the optimum when Held-Karp can give it (n ≤ 12), else against the MST lower bound (an upper bound on the gap).
            val vsMst = n > 12
            fun gap(f: (Case) -> Double) = cs.map { c ->
                val ref = if (vsMst) mstLowerBound(c.points, c.start) else exactRoute(c.points, c.start).meters
                100 * (f(c) / ref - 1)
            }.average()
            Row(
                n, brute, hk, heur,
                gap { heuristicRoute(it.points, it.start, twoOpt = false, orOpt = false).meters },
                gap { heuristicRoute(it.points, it.start, twoOpt = true, orOpt = false).meters },
                gap { heuristicRoute(it.points, it.start).meters },
                vsMst,
            )
        }
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
        val md = routeDoc(table, cutoff)
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

private fun routeDoc(table: String, cutoff: Int) = """## 동네 묶음 최단 경로

동네 묶음의 걷기 동선은 **출발점이 있고 돌아오지 않는 외판원 문제(Open-path TSP)**예요. 모든 장소를 한 번씩 들르는 가장 짧은 순서를 찾아요.

`./gradlew :app:testDebugUnitTest -ProuteBench -ProuteWrite`로 다시 만들 수 있어요 (시드 고정).

### 알고리즘

| 방법 | 시간 복잡도 | 정확도 | 앱에서 쓰는 곳 |
|---|---|---|---|
| 완전 탐색 | O(n!) | 최적 | 테스트에서 정답 확인용 |
| **Held-Karp 동적 계획법** | O(n²·2ⁿ), 메모리 O(n·2ⁿ) | 최적 | 장소 ${EXACT_MAX}곳 이하 |
| **가까운 곳부터 잇기 + 2-opt + Or-opt** | 한 바퀴 O(n³) (구현), 몇 바퀴 반복 | 근사 | 장소 ${EXACT_MAX + 1}곳 이상 |
| 최소 신장 트리(MST, Prim) | O(n²) | 하한 | 근사의 오차 상한 계산 |

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
- Consumes: `bundles`, `Bundle`, `fallbackBundleName` (Task 2); `shortestRoute`, `RouteResult`, `walkMinutes` (Task 1); `RegionNames.names/resolve`, `KakaoHttp.regionName` (Task 4).
- Produces: `data class BundleItem(val bundle: Bundle, val name: String, val route: RouteResult, val start: Coords?, val minutes: Int)`; `HomeViewModel.bundles: StateFlow<List<BundleItem>>`, `openBundle: StateFlow<String?>` (bundle id), `fun toggleBundle(id: String)`, `fun openBundleFor(placeId: String)`; `MapPlace.number: Int?`; `SpotMap(route: List<Coords> = emptyList())`.

This task is Compose/Android glue; it is verified by build and on the emulator in Task 7.

- [ ] **Step 1: View model** — in `HomeViewModel.kt` add:

```kotlin
data class BundleItem(val bundle: Bundle, val name: String, val route: RouteResult, val start: Coords?, val minutes: Int)

    /** Start from the user only when they are in the neighborhood (within 1km of a member). */
    private val _openBundle = MutableStateFlow<String?>(null)
    val openBundle: StateFlow<String?> = _openBundle.asStateFlow()

    val bundles: StateFlow<List<BundleItem>> = combine(c.repo.places, here, c.regionNames.names) { list, at, _ ->
        bundles(list, System.currentTimeMillis(), at).map { b ->
            val start = at?.takeIf { a -> b.places.any { distanceMeters(a, Coords(it.latitude, it.longitude)) <= 1000 } }
            val route = shortestRoute(b.places.map { Coords(it.latitude, it.longitude) }, start)
            BundleItem(b, c.regionNames.cached(b.center) ?: fallbackBundleName(b), route, start, walkMinutes(route.meters))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
  - Collect `val bundleItems by vm.bundles.collectAsState()` and `val openBundle by vm.openBundle.collectAsState()`; `val open = bundleItems.firstOrNull { it.bundle.id == openBundle }`.
  - Map inputs: when `open != null`, number the places by `open.route.order` (`number = index + 1` for members) and pass `route = listOfNotNull(open.start) + open.route.order.map { Coords(open.bundle.places[it].latitude, open.bundle.places[it].longitude) }`.
  - In the `Tab.PLACES` branch, before the places items:

```kotlin
                        if (bundleItems.isNotEmpty()) item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(bundleItems, key = { it.bundle.id }) { b ->
                                    BundleCard(b, open = b.bundle.id == openBundle, onClick = { vm.toggleBundle(b.bundle.id) })
                                }
                            }
                        }
                        val shown = if (open != null) open.route.order.map { open.bundle.places[it] } else places
```

    and iterate `items(shown, key = { it.id })` instead of `places`, passing `number = open?.let { o -> o.route.order.indexOfFirst { o.bundle.places[it].id == p.id } + 1 }` to `PlaceRow` (add an optional `number: Int? = null` parameter that, when set, shows a small numbered circle before the name).
  - Add:

```kotlin
@Composable
private fun BundleCard(b: BundleItem, open: Boolean, onClick: () -> Unit) {
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
