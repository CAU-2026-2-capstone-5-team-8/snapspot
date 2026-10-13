package com.capstone.snapspot.data

import com.capstone.snapspot.core.Coords
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RegionNamesTest {
    @TempDir
    lateinit var dir: File
    private val seongsu = Coords(37.5446, 127.0560)

    @Test
    fun `resolves once, stores the display name per cell, survives a restart`() = runTest {
        var calls = 0
        val names = RegionNames(dir)
        assertNull(names.cached(seongsu))
        assertEquals("성수동", names.resolve(seongsu) { calls++; "성수2가1동" })
        assertEquals("성수동", names.resolve(Coords(37.5447, 127.0561)) { calls++; "다른동" })
        assertEquals(1, calls)
        assertEquals("성수동", RegionNames(dir).cached(seongsu))
    }

    @Test
    fun `lookup failures are not cached`() = runTest {
        val names = RegionNames(dir)
        assertNull(names.resolve(seongsu) { throw java.io.IOException("offline") })
        assertNull(names.resolve(seongsu) { null })
        assertEquals("성수동", names.resolve(seongsu) { "성수1가1동" })
    }
}
