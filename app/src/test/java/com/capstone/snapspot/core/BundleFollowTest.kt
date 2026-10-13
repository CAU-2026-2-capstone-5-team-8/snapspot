package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BundleFollowTest {
    private fun bundle(vararg ids: String) = Bundle(ids.min(), ids.map { place(id = it) })

    @Test
    fun `a bundle that gained a place with a smaller id keeps its UI state`() {
        val old = mapOf("b" to setOf("b", "c", "d"))
        assertEquals(mapOf("b" to "a"), followBundles(old, listOf(bundle("a", "b", "c", "d"))))
    }

    @Test
    fun `a split bundle follows the part sharing the most places`() {
        val old = mapOf("a" to setOf("a", "b", "c", "x", "y"))
        // The x/y/c part shares 3 places (its id is "c", the smallest member).
        assertEquals(mapOf("a" to "c"), followBundles(old, listOf(bundle("a", "b", "z"), bundle("x", "y", "c"))))
    }

    @Test
    fun `a bundle that is gone maps to nothing`() {
        assertEquals(emptyMap<String, String>(), followBundles(mapOf("a" to setOf("a", "b", "c")), listOf(bundle("p", "q", "r"))))
    }
}

class RouteMemoTest {
    private val a = Coords(37.5446, 127.0560)
    private val b = Coords(37.5456, 127.0570)

    @Test
    fun `same stops and start are solved once`() {
        var calls = 0
        val memo = RouteMemo(4)
        val solve = { s: List<Stop>, st: Coords? -> calls++; shortestRoute(s, st) }
        val r1 = memo.route(stopsOf(listOf(a, b)), null, solve)
        val r2 = memo.route(stopsOf(listOf(a, b)), null, solve)
        assertEquals(1, calls)
        assertEquals(r1, r2)
        memo.route(stopsOf(listOf(a, b)), a, solve)
        assertEquals(2, calls)
    }

    @Test
    fun `oldest entries are dropped past capacity`() {
        var calls = 0
        val memo = RouteMemo(2)
        val solve = { s: List<Stop>, st: Coords? -> calls++; shortestRoute(s, st) }
        memo.route(stopsOf(listOf(a)), null, solve)
        memo.route(stopsOf(listOf(b)), null, solve)
        memo.route(stopsOf(listOf(a, b)), null, solve)
        memo.route(stopsOf(listOf(a)), null, solve)
        assertEquals(4, calls)
    }
}
