package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapViewTest {
    private val center = Coords(37.5447, 127.0560)

    /** `m` meters north of the center (1° latitude ≈ 111.2km). */
    private fun north(m: Double) = Coords(center.latitude + m / 111_195.0, center.longitude)

    @Test
    fun `the map searches first, then only after moving more than 1_5km`() {
        assertTrue(needsMapSearch(null, center))
        assertFalse(needsMapSearch(center, north(1400.0)))
        assertTrue(needsMapSearch(center, north(1600.0)))
    }

    @Test
    fun `only branches within 1km are drawn`() {
        val pins = mapOf("b|starbucks|1" to north(900.0), "b|starbucks|2" to north(1100.0))
        assertEquals(setOf("b|starbucks|1"), limitBranches(pins, center))
    }

    @Test
    fun `each brand keeps its 5 nearest`() {
        val star = (1..7).associate { "b|starbucks|$it" to north(it * 100.0) }
        val mega = (1..2).associate { "b|mega|$it" to north(it * 100.0 + 50) }
        val kept = limitBranches(star + mega, center)
        assertEquals((1..5).map { "b|starbucks|$it" }.toSet() + mega.keys, kept)
    }

    @Test
    fun `without a center nothing is left out`() {
        val pins = mapOf("b|starbucks|1" to north(5000.0))
        assertEquals(pins.keys, limitBranches(pins, null))
    }
}
