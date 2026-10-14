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
    fun `with four coupon brands the heuristic stays close to exact`() {
        // 7 places + 4 brands of 5–8 branches each: the case where moving a stop must also re-pick its branch.
        val gaps = (1..20).map { seed ->
            val r = Random(seed)
            val st = List(7) { listOf(near(r)) } + List(4) { List(5 + r.nextInt(4)) { near(r) } }
            val start = if (seed % 2 == 0) origin else null
            heuristicRoute(st, start).meters / exactRoute(st, start).meters - 1
        }
        assertTrue(gaps.average() < 0.02, "average gap ${gaps.average()}")
        assertTrue(gaps.max() < 0.10, "worst gap ${gaps.max()}")
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
