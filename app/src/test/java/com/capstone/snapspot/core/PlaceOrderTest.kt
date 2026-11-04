package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PlaceOrderTest {
    private val here = Coords(37.5446, 127.0560)

    private fun length(stops: List<Stop>, start: Coords?, result: RouteResult): Double =
        (listOfNotNull(start) + result.order.indices.map { stops[result.order[it]][result.picks[it]] })
            .zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    /** Independent enumeration: check chosen-place order only at completed paths. */
    private fun oracle(stops: List<Stop>, start: Coords?, ordered: List<Int>): Double {
        var best = Double.MAX_VALUE
        val path = mutableListOf<Int>()
        val used = BooleanArray(stops.size)
        fun visit(last: Coords?, meters: Double) {
            if (meters >= best) return
            if (path.size == stops.size) {
                if (path.filter { it in ordered } == ordered) best = meters
                return
            }
            for (s in stops.indices) {
                if (used[s] || path.isEmpty() && s != ordered.first()) continue
                used[s] = true
                path += s
                for (point in stops[s]) visit(point, meters + (last?.let { distanceMeters(it, point) } ?: 0.0))
                path.removeAt(path.lastIndex)
                used[s] = false
            }
        }
        visit(start, 0.0)
        return best
    }

    @Test
    fun `ordered places match independent exhaustive paths with coupons between places`() {
        for (seed in 1..12) {
            val random = Random(seed)
            fun point() = Coords(here.latitude + random.nextDouble() * 0.012, here.longitude + random.nextDouble() * 0.012)
            val stops = List(3) { listOf(point()) } + List(3) { List(2) { point() } }
            val order = if (seed % 2 == 0) listOf(2, 0, 1) else listOf(1, 2, 0)
            val start = here.takeIf { seed % 3 != 0 }
            val result = routeWithPlaceOrder(stops, start, order)
            assertEquals(order, result.order.filter { it in order })
            assertEquals(order.first(), result.order.first())
            assertEquals(stops.indices.toSet(), result.order.toSet())
            assertEquals(stops.size, result.order.size)
            assertEquals(oracle(stops, start, order), result.meters, 1e-6, "seed $seed")
            assertEquals(length(stops, start, result), result.meters, 1e-6)
        }
    }

    @Test
    fun `a coupon on the way can be visited before the last chosen place`() {
        fun at(delta: Double) = here.copy(latitude = here.latitude + delta)
        val stops = stopsOf(listOf(at(0.0), at(0.004), at(0.008))) + listOf(listOf(at(0.03), at(0.002)))
        val order = listOf(2, 0, 1)
        val result = routeWithPlaceOrder(stops, here, order)
        assertEquals(order, result.order.filter { it in order })
        assertEquals(2, result.order.first())
        assertTrue(result.order.indexOf(3) < result.order.indexOf(1))
        assertEquals(1, result.picks[result.order.indexOf(3)])
        assertEquals(length(stops, here, result), result.meters, 1e-6)
    }

    @Test
    fun `large courses preserve every chosen position through all heuristic improvements`() {
        for (seed in 1..10) {
            val random = Random(seed)
            fun point() = Coords(here.latitude + random.nextDouble() * 0.03, here.longitude + random.nextDouble() * 0.03)
            val places = EXACT_MAX + 3
            val stops = List(places) { listOf(point()) } + List(3) { List(4) { point() } }
            val order = (0 until places).shuffled(random)
            val result = routeWithPlaceOrder(stops, here, order)
            assertEquals(order, result.order.filter { it < places }, "seed $seed")
            assertEquals(order.first(), result.order.first())
            assertEquals(stops.size, result.order.distinct().size)
            assertEquals(length(stops, here, result), result.meters, 1e-6)
            assertTrue(result.meters + 1e-6 >= lowerBound(stops, here))
        }
    }

    @Test
    fun `changing second and third places does not reuse the old cached order`() {
        val stops = stopsOf(List(3) { here.copy(latitude = here.latitude + it * 0.002) })
        val memo = RouteMemo(4)
        val first = memo.routeOrdered(stops, here, listOf(0, 1, 2))
        val second = memo.routeOrdered(stops, here, listOf(0, 2, 1))
        assertEquals(listOf(0, 1, 2), first.order)
        assertEquals(listOf(0, 2, 1), second.order)
        assertEquals(first, memo.routeOrdered(stops, here, listOf(0, 1, 2)))
    }

    @Test
    fun `moving places keeps membership and supports movement in both directions`() {
        val initial = listOf("a", "b", "c")
        val moved = moveRoutePlace(initial, "c", "a")
        assertEquals(listOf("c", "a", "b"), moved)
        assertEquals(initial.toSet(), moved.toSet())
        assertEquals(initial, moveRoutePlace(moved, "c", "b"))
        assertEquals(initial, moveRoutePlace(initial, "outside", "a"))
        assertEquals(initial, moveRoutePlace(initial, "a", "outside"))
        assertEquals(listOf("a", "b", "c"), initial)
    }

    @Test
    fun `default order recommends the nearest place and works with no position`() {
        val a = place(id = "a", latitude = here.latitude + 0.001, longitude = here.longitude)
        val b = place(id = "b", latitude = here.latitude + 0.005, longitude = here.longitude)
        val c = place(id = "c", latitude = here.latitude + 0.002, longitude = here.longitude)
        assertEquals(listOf("a", "c", "b"), recommendRouteOrder(listOf(b, c, a), here))
        assertEquals(setOf("a", "b", "c"), recommendRouteOrder(listOf(a, b, c), null).toSet())
        assertEquals(emptyList<String>(), recommendRouteOrder(emptyList(), here))
    }

    @Test
    fun `empty order preserves existing solver and invalid ordered places are rejected`() {
        val stops = listOf(listOf(here), listOf(here, here.copy(latitude = here.latitude + 0.002)))
        assertEquals(shortestRoute(stops, here), routeWithPlaceOrder(stops, here, emptyList()))
        assertEquals(RouteResult(emptyList(), emptyList(), 0.0), routeWithPlaceOrder(emptyList(), null, emptyList()))
        assertThrows(IllegalArgumentException::class.java) { routeWithPlaceOrder(stops, here, listOf(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { routeWithPlaceOrder(stops, here, listOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { routeWithPlaceOrder(stops, here, listOf(2)) }
    }
}
