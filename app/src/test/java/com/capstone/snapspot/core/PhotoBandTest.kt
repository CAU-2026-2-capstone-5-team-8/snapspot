package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhotoBandTest {
    /** One score per row: `#` a photo row, `.` a text/background row. */
    private fun rows(s: String) = FloatArray(s.length) { if (s[it] == '#') 0.9f else 0.1f }

    @Test
    fun `mid tones count, white and black background do not`() {
        assertEquals(1f, photoRowScore(intArrayOf(120, 60, 200)))
        assertEquals(0f, photoRowScore(intArrayOf(255, 250, 0, 10)))
        assertEquals(0.5f, photoRowScore(intArrayOf(255, 128)))
    }

    @Test
    fun `the picture is the longest run of photo rows`() {
        // status bar, header, a 40-row photo, caption, a short colored button.
        val s = ".".repeat(10) + "#".repeat(40) + ".".repeat(30) + "####" + ".".repeat(16)
        assertEquals(10..49, photoBand(rows(s)))
    }

    @Test
    fun `a thin seam inside the picture does not split it`() {
        val s = ".".repeat(10) + "#".repeat(20) + "." + "#".repeat(20) + ".".repeat(49)
        assertEquals(10..50, photoBand(rows(s)))
    }

    @Test
    fun `text-only screenshots have no picture`() {
        assertNull(photoBand(rows(".".repeat(90) + "#####" + ".".repeat(5))))
    }

    @Test
    fun `the window is centered on the picture and kept inside the image`() {
        assertEquals(9, focusTop(10..49, 100, 40))
        assertEquals(0, focusTop(0..9, 100, 40))
        assertEquals(60, focusTop(90..99, 100, 40))
        assertEquals(30, focusTop(null, 100, 40))
    }
}
