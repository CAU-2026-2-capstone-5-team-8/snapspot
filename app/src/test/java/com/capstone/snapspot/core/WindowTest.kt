package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowTest {
    private val here = Coords(37.5446, 127.0565)

    /** n spots going east from `here`, 100m apart (~88km per degree of longitude at 37.5°N). */
    private fun line(group: String, n: Int, startM: Int = 100, tier: Tier = Tier.NORMAL, rank: Int = 0): List<Spot> =
        (0 until n).map { i ->
            Spot(
                id = "$group-$i", group = group, tier = tier, rank = rank,
                latitude = here.latitude, longitude = here.longitude + (startM + i * 100) / 88_000.0, radius = 200.0,
            )
        }

    private fun ids(p: Plan) = p.regions.filter { it.identifier != REFRESH_ID }.map { it.identifier }

    @Test
    fun `reserves one exit-only refresh region centered on the user`() {
        val p = planRegions(here, line("A", 5), slots = 10, refreshRadius = 1000.0)
        val refresh = p.regions.firstOrNull { it.identifier == REFRESH_ID }
        assertNotNull(refresh)
        assertTrue(refresh!!.notifyOnExit)
        assertFalse(refresh.notifyOnEnter)
        assertEquals(1000.0, refresh.radius)
        assertEquals(here.latitude, refresh.latitude)
    }

    @Test
    fun `never exceeds the slot limit, refresh region included`() {
        val p = planRegions(here, line("A", 50) + line("B", 50, rank = 1), slots = 20, refreshRadius = 1000.0, perGroupCap = 100)
        assertEquals(20, p.regions.size)
    }

    @Test
    fun `picks the nearest spots within each group`() {
        val p = planRegions(here, line("A", 10).reversed(), slots = 4, refreshRadius = 1000.0)
        assertEquals(listOf("A-0", "A-1", "A-2"), ids(p))
    }

    @Test
    fun `without a known position - no refresh region, input order kept`() {
        val p = planRegions(null, line("place", 3, tier = Tier.PLACE), slots = 10, refreshRadius = 1000.0)
        assertEquals(listOf("place-0", "place-1", "place-2"), p.regions.map { it.identifier })
    }

    @Test
    fun `saved places fill slots before any coupon branch`() {
        val spots = line("place", 5, 5000, Tier.PLACE) + line("A", 50)
        val p = planRegions(here, spots, slots = 4, refreshRadius = 1000.0)
        assertEquals(mapOf("place" to 3), p.byGroup)
    }

    @Test
    fun `saved places are not capped at 7`() {
        val p = planRegions(here, line("place", 12, tier = Tier.PLACE), slots = 90, refreshRadius = 1000.0)
        assertEquals(12, p.byGroup["place"])
    }

    @Test
    fun `urgent brand goes before normal brands`() {
        val spots = line("A", 10) + line("U", 10, 300, Tier.URGENT)
        val p = planRegions(here, spots, slots = 6, refreshRadius = 1000.0)
        assertEquals(mapOf("U" to 5), p.byGroup)
    }

    @Test
    fun `each brand gets at most 7 nearest branches`() {
        val p = planRegions(here, line("A", 30), slots = 90, refreshRadius = 1000.0)
        assertEquals(7, p.byGroup["A"])
        assertEquals((0 until 7).map { "A-$it" }, ids(p))
    }

    @Test
    fun `brands in the same tier share slots round-robin, earlier rank first`() {
        val spots = line("B", 10, 300, rank = 1) + line("A", 10, rank = 0)
        val p = planRegions(here, spots, slots = 6, refreshRadius = 1000.0)
        assertEquals(mapOf("A" to 3, "B" to 2), p.byGroup)
        assertEquals("A-0", ids(p).first())
    }
}
