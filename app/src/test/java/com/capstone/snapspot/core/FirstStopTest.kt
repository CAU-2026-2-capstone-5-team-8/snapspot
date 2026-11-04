package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.random.Random

class FirstStopTest {
    private val origin = Coords(37.5446, 127.0560)
    private val a = place(id = "a", latitude = origin.latitude + 0.001, longitude = origin.longitude)
    private val b = place(id = "b", latitude = origin.latitude + 0.003, longitude = origin.longitude)
    private val c = place(id = "c", latitude = origin.latitude + 0.006, longitude = origin.longitude)

    @Test
    fun `recommend the nearest selected place rather than the saved list order`() {
        assertEquals(a, firstRoutePlace(listOf(c, b, a), null, origin))
        assertEquals(b, firstRoutePlace(listOf(c, b), null, origin))
    }

    @Test
    fun `a chosen first place wins even after the user moves near another place`() {
        assertEquals(c, firstRoutePlace(listOf(a, b, c), "c", origin))
        assertEquals(c, firstRoutePlace(listOf(a, b, c), "c", Coords(b.latitude, b.longitude)))
    }

    @Test
    fun `removing a preferred place recommends another selected place`() {
        assertEquals(b, firstRoutePlace(listOf(c, b), "a", origin))
        assertNull(firstRoutePlace(emptyList(), "a", origin))
    }

    @Test
    fun `unknown position allows a manual choice and otherwise leaves the order automatic`() {
        assertEquals(c, firstRoutePlace(listOf(a, b, c), "c", null))
        assertNull(firstRoutePlace(listOf(a, b), null, null))
        assertNull(firstRoutePlace(listOf(a, b), "missing", null))
    }

    @Test
    fun `equal distance recommendations stay stable when the list is reordered`() {
        val same = b.copy(latitude = a.latitude, longitude = a.longitude)
        assertEquals(a, firstRoutePlace(listOf(same, a), null, origin))
        assertEquals(a, firstRoutePlace(listOf(a, same), null, origin))
    }

    private fun pathLength(stops: List<Stop>, start: Coords?, route: RouteResult): Double {
        val points = listOfNotNull(start) + route.order.indices.map { stops[route.order[it]][route.picks[it]] }
        return points.zipWithNext().sumOf { (from, to) -> distanceMeters(from, to) }
    }

    @Test
    fun `fixed first place matches constrained brute force including coupon branch choices`() {
        for (seed in 1..12) {
            val random = Random(seed)
            fun near() = Coords(origin.latitude + random.nextDouble() * 0.01, origin.longitude + random.nextDouble() * 0.01)
            val stops = List(4) { listOf(near()) } + List(2) { List(3) { near() } }
            val first = seed % 4
            val start = if (seed % 2 == 0) origin else null
            val remaining = stops.filterIndexed { i, _ -> i != first }
            val lead = start?.let { distanceMeters(it, stops[first].single()) } ?: 0.0
            val expected = lead + bruteForceRoute(remaining, stops[first].single()).meters
            val actual = routeWithFirstPlace(stops, start, first)

            assertEquals(first, actual.order.first(), "seed $seed")
            assertEquals(stops.indices.toSet(), actual.order.toSet())
            assertEquals(stops.size, actual.order.distinct().size)
            assertEquals(expected, actual.meters, 1e-6, "seed $seed")
            assertEquals(pathLength(stops, start, actual), actual.meters, 1e-6)
        }
    }

    @Test
    fun `a nearby coupon cannot precede the chosen place`() {
        val stops = stopsOf(listOf(Coords(a.latitude, a.longitude), Coords(c.latitude, c.longitude))) +
            listOf(listOf(origin, Coords(b.latitude, b.longitude)))
        val actual = routeWithFirstPlace(stops, origin, 1)
        assertEquals(1, actual.order.first())
        assertEquals(0, actual.picks.first())
        assertEquals(pathLength(stops, origin, actual), actual.meters, 1e-6)
    }

    @Test
    fun `the heuristic keeps a fixed first place and visits all remaining stops once`() {
        val random = Random(42)
        fun near() = Coords(origin.latitude + random.nextDouble() * 0.03, origin.longitude + random.nextDouble() * 0.03)
        val stops = List(EXACT_MAX + 3) { listOf(near()) } + List(2) { List(3) { near() } }
        val actual = routeWithFirstPlace(stops, origin, 5)
        assertEquals(5, actual.order.first())
        assertEquals(stops.size, actual.order.size)
        assertEquals(stops.indices.toSet(), actual.order.toSet())
        assertEquals(pathLength(stops, origin, actual), actual.meters, 1e-6)
    }

    @Test
    fun `a single fixed place includes only the leg from the start`() {
        val stops = listOf(listOf(Coords(a.latitude, a.longitude)))
        assertEquals(RouteResult(listOf(0), listOf(0), 0.0), routeWithFirstPlace(stops, null, 0))
        assertEquals(distanceMeters(origin, stops.single().single()), routeWithFirstPlace(stops, origin, 0).meters, 1e-6)
    }

    @Test
    fun `without a fixed first place the existing solver is unchanged`() {
        val stops = stopsOf(listOf(origin, Coords(a.latitude, a.longitude), Coords(b.latitude, b.longitude)))
        assertEquals(shortestRoute(stops, origin), routeWithFirstPlace(stops, origin, null))
        assertEquals(RouteResult(emptyList(), emptyList(), 0.0), routeWithFirstPlace(emptyList(), null, null))
    }

    @Test
    fun `changing only the first place cannot reuse the previous cached route`() {
        val stops = stopsOf(listOf(Coords(a.latitude, a.longitude), Coords(b.latitude, b.longitude), Coords(c.latitude, c.longitude)))
        val memo = RouteMemo(4)
        var solves = 0
        val solve = { points: List<Stop>, start: Coords? -> solves++; shortestRoute(points, start) }
        assertEquals(0, memo.route(stops, origin, solve, 0).order.first())
        assertEquals(1, memo.route(stops, origin, solve, 1).order.first())
        assertEquals(0, memo.route(stops, origin, solve, 0).order.first())
        assertEquals(2, solves)
    }

    @Test
    fun `only a valid single-option place may be fixed first`() {
        val stops = listOf(listOf(origin), listOf(origin, Coords(a.latitude, a.longitude)))
        assertThrows(IllegalArgumentException::class.java) { routeWithFirstPlace(stops, origin, -1) }
        assertThrows(IllegalArgumentException::class.java) { routeWithFirstPlace(stops, origin, 2) }
        assertThrows(IllegalArgumentException::class.java) { routeWithFirstPlace(stops, origin, 1) }
    }
}
