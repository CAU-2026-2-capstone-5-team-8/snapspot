package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Branch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CouponStopsTest {
    private val places = listOf(place(id = "a", latitude = 37.5446, longitude = 127.0560), place(id = "b", latitude = 37.5450, longitude = 127.0565))
    private val center = Coords(37.5448, 127.05625)
    private fun branch(id: String, lat: Double, lng: Double) = Branch(kakaoId = id, name = id, address = "", latitude = lat, longitude = lng)

    @Test
    fun `one brand failing keeps the others and counts the failure`() = runTest {
        val r = nearbyCouponStops(places, center, listOf("스타벅스", "메가커피", "올리브영")) { brand ->
            when (brand) {
                "메가커피" -> throw java.io.IOException("offline")
                "올리브영" -> listOf(branch("far", 37.60, 127.10))
                else -> listOf(branch("near", 37.5447, 127.0561))
            }
        }
        assertEquals(listOf("스타벅스"), r.stops.map { it.brand })
        assertEquals(1, r.failed)
    }

    @Test
    fun `only branches within 500m of a place, nearest 8 to the center`() = runTest {
        val many = (1..12).map { branch("b$it", 37.5448 + it * 0.0001, 127.05625) } + branch("far", 37.56, 127.05625)
        val r = nearbyCouponStops(places, center, listOf("스타벅스")) { many }
        assertEquals((1..8).map { "b$it" }, r.stops.single().branches.map { it.kakaoId })
        assertEquals(0, r.failed)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        assertThrows<CancellationException> {
            nearbyCouponStops(places, center, listOf("스타벅스")) { throw CancellationException("gone") }
        }
    }
}
