package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DistanceTest {
    @Test
    fun `seongsu to ttukseom is under 1km`() {
        val d = distanceMeters(Coords(37.5446, 127.0565), Coords(37.5474, 127.0473))
        assertTrue(d in 800.0..900.0, "got $d")
    }

    @Test
    fun `formats meters below 1km and kilometers above`() {
        assertEquals("933m", formatDistance(933.2))
        assertEquals("2.1km", formatDistance(2140.0))
    }
}
