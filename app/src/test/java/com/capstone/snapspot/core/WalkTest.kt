package com.capstone.snapspot.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkTest {
    private fun pts(n: Int) = (0 until n).map { Coords(37.54 + it * 0.001, 127.05) }

    @Test
    fun `fewer than two points need no call`() {
        assertTrue(walkChunks(pts(0)).isEmpty())
        assertTrue(walkChunks(pts(1)).isEmpty())
    }

    @Test
    fun `up to seven points go in one call`() {
        assertEquals(listOf(pts(2)), walkChunks(pts(2)))
        assertEquals(listOf(pts(7)), walkChunks(pts(7)))
    }

    @Test
    fun `longer routes split into chunks that share their joint`() {
        val p = pts(12)
        val chunks = walkChunks(p)
        assertEquals(listOf(p.subList(0, 7), p.subList(6, 12)), chunks)
        assertTrue(chunks.all { it.size <= WALK_MAX_POINTS })
        // 13 points: 0..6, 6..12 — exactly two calls; 14 points need a third.
        assertEquals(2, walkChunks(pts(13)).size)
        assertEquals(3, walkChunks(pts(14)).size)
    }

    @Test
    fun `chunks join into one line without repeating the joint, totals add up`() = runTest {
        val api = WalkApi { points -> WalkPath(points, meters = 100 * (points.size - 1), seconds = 60 * (points.size - 1)) }
        val p = pts(12)
        val w = walkPath(api, p)
        assertEquals(p, w.line)
        assertEquals(1100, w.meters)
        assertEquals(660, w.seconds)
        assertEquals(11, w.minutes)
    }

    @Test
    fun `minutes round up`() = assertEquals(2, WalkPath(emptyList(), 0, 61).minutes)

    @Test
    fun `key ignores small moves of the start but not a different stop`() {
        val stops = pts(3)
        val a = walkKey(Coords(37.54501, 127.05501), stops)
        assertEquals(a, walkKey(Coords(37.54520, 127.05480), stops))
        assertNotEquals(a, walkKey(Coords(37.54700, 127.05501), stops))
        assertNotEquals(a, walkKey(Coords(37.54501, 127.05501), stops.reversed()))
        assertNotEquals(a, walkKey(null, stops))
    }
}
