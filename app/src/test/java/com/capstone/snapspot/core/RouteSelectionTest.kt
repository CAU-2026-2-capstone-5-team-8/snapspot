package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Branch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class RouteSelectionTest {
    private val a = place(id = "a", latitude = 37.54, longitude = 127.05)
    private val b = place(id = "b", latitude = 37.541, longitude = 127.05)
    private val c = place(id = "c", latitude = 37.542, longitude = 127.05)
    private val far = place(id = "far", latitude = 37.60, longitude = 127.10)

    @Test
    fun `a course can remove a bundle member and add a place outside the bundle`() {
        val saved = listOf(a, b, c, far)
        val original = bundles(saved, NOW).single()
        val chosen = selectedRoutePlaces(saved, setOf("a", "c", "far"), NOW)
        val route = shortestRoute(stopsOf(chosen.map { Coords(it.latitude, it.longitude) }), null)

        assertEquals(setOf("a", "c", "far"), route.order.map { chosen[it].id }.toSet())
        assertEquals(chosen.size, route.order.distinct().size)
        assertFalse("b" in route.order.map { chosen[it].id })
        assertEquals(listOf("a", "b", "c"), original.places.map { it.id })
        assertEquals(listOf(original), bundles(saved, NOW))
    }

    @Test
    fun `deleted and ended places are removed even from an older selection`() {
        val ended = c.copy(endDate = "2026-01-01")
        val chosen = selectedRoutePlaces(listOf(a, ended), setOf("a", "b", "c", "gone"), NOW)
        assertEquals(listOf(a), chosen)
        assertEquals(emptyList<com.capstone.snapspot.core.model.Place>(), selectedRoutePlaces(listOf(a), emptySet(), NOW))
    }

    @Test
    fun `saved list distance sorting does not change solver input or duplicate a stop`() {
        val ids = setOf("b", "a")
        assertEquals(listOf(a, b), selectedRoutePlaces(listOf(b, a, b), ids, NOW))
        assertEquals(selectedRoutePlaces(listOf(a, b), ids, NOW), selectedRoutePlaces(listOf(b, a), ids, NOW))
    }

    @Test
    fun `changed selections get distinct routes instead of a stale cached course`() {
        val memo = RouteMemo(4)
        var solves = 0
        val solve = { stops: List<Stop>, start: Coords? -> solves++; shortestRoute(stops, start) }
        val first = selectedRoutePlaces(listOf(a, b, c, far), setOf("a", "b", "c"), NOW)
        val second = selectedRoutePlaces(listOf(a, b, c, far), setOf("a", "far"), NOW)
        memo.route(stopsOf(first.map { Coords(it.latitude, it.longitude) }), null, solve)
        val result = memo.route(stopsOf(second.map { Coords(it.latitude, it.longitude) }), null, solve)
        assertEquals(2, solves)
        assertEquals(setOf("a", "far"), result.order.map { second[it].id }.toSet())
    }

    @Test
    fun `coupon branches are searched near each selected neighborhood and duplicates are joined`() = runTest {
        val nearA = Branch("near-a", "스타벅스 A", "", a.latitude, a.longitude)
        val nearFar = Branch("near-far", "스타벅스 B", "", far.latitude, far.longitude)
        val excluded = Branch("excluded", "스타벅스 제외", "", 37.58, 127.08)
        val asked = mutableListOf<Coords>()
        val found = routeCouponStops(listOf(a, far), listOf("스타벅스")) { _, at ->
            asked += at
            listOf(nearA, nearFar, nearA, excluded)
        }

        assertEquals(2, asked.size)
        assertEquals(setOf("near-a", "near-far"), found.stops.single().branches.map { it.kakaoId }.toSet())
        assertEquals(2, found.stops.single().branches.size)
        assertEquals(0, found.failed)
    }

    @Test
    fun `places in one cache cell share one branch search`() = runTest {
        val at = Coords(37.5402, 127.0502)
        val nearby = at.copy(latitude = at.latitude + 0.00001)
        var calls = 0
        routeCouponStops(listOf(a.copy(latitude = at.latitude, longitude = at.longitude), b.copy(latitude = nearby.latitude, longitude = nearby.longitude)), listOf("스타벅스")) { _, _ ->
            calls++
            emptyList()
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a failed neighborhood keeps available coupon branches and reports incomplete results`() = runTest {
        val nearA = Branch("near-a", "스타벅스 A", "", a.latitude, a.longitude)
        val found = routeCouponStops(listOf(a, far), listOf("스타벅스")) { _, at ->
            if (at.latitude == far.latitude) error("offline")
            listOf(nearA)
        }
        assertEquals(listOf(nearA), found.stops.single().branches)
        assertEquals(1, found.failed)
    }

    @Test
    fun `cancelling an obsolete branch search is propagated`() {
        assertThrows(CancellationException::class.java) {
            runTest { routeCouponStops(listOf(a), listOf("스타벅스")) { _, _ -> throw CancellationException("course changed") } }
        }
    }
}
