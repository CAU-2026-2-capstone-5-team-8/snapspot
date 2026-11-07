package com.capstone.snapspot.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EmbedCacheTest {
    @TempDir
    lateinit var dir: File

    private val asked = mutableListOf<List<String>>()
    private val fetch: suspend (List<String>) -> List<FloatArray> = { texts ->
        asked += texts
        texts.map { floatArrayOf(it.length.toFloat(), 0.5f, -1.25f) }
    }

    @Test
    fun `fetches only texts it has not seen, keeps order, survives a restart`() = runTest {
        val cache = EmbedCache(dir, fetch)
        val first = cache.embed(listOf("가", "나나", "가"))
        assertEquals(listOf(listOf("가", "나나")), asked)
        assertArrayEquals(floatArrayOf(1f, 0.5f, -1.25f), first[0])
        assertArrayEquals(floatArrayOf(2f, 0.5f, -1.25f), first[1])
        assertArrayEquals(first[0], first[2])

        val again = EmbedCache(dir, fetch).embed(listOf("나나", "다다다"))
        assertEquals(listOf("다다다"), asked.last())
        assertArrayEquals(floatArrayOf(2f, 0.5f, -1.25f), again[0])
    }

    @Test
    fun `drops the least recently used past the limit`() = runTest {
        var t = 0L
        val cache = EmbedCache(dir, fetch, max = 2, clock = { t++ })
        cache.embed(listOf("a"))
        cache.embed(listOf("b"))
        cache.embed(listOf("a"))
        cache.embed(listOf("c"))
        asked.clear()
        cache.embed(listOf("a", "b"))
        assertEquals(listOf(listOf("b")), asked)
    }
}
