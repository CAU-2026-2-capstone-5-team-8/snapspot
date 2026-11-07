package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TasteTest {
    private fun v(vararg x: Float) = floatArrayOf(*x)

    @Test
    fun `cosine is 1 for the same direction, 0 for orthogonal, -1 for opposite`() {
        assertEquals(1.0, cosine(v(1f, 0f), v(3f, 0f)), 1e-6)
        assertEquals(0.0, cosine(v(1f, 0f), v(0f, 2f)), 1e-6)
        assertEquals(-1.0, cosine(v(1f, 1f), v(-1f, -1f)), 1e-6)
    }

    @Test
    fun `cosine of a zero vector is 0, not NaN`() {
        assertEquals(0.0, cosine(v(0f, 0f), v(1f, 0f)))
    }

    @Test
    fun `vectors of different sizes are rejected`() {
        assertThrows<IllegalArgumentException> { cosine(v(1f), v(1f, 0f)) }
    }

    @Test
    fun `taste is the normalized mean, so a long vector does not outweigh a short one`() {
        val t = tasteVector(listOf(v(10f, 0f), v(0f, 1f)))!!
        assertEquals(t[0].toDouble(), t[1].toDouble(), 1e-6)
        assertEquals(1.0, Math.sqrt((t[0] * t[0] + t[1] * t[1]).toDouble()), 1e-6)
    }

    @Test
    fun `no saved places means no taste`() {
        assertNull(tasteVector(emptyList()))
    }

    @Test
    fun `candidates are ranked by similarity to the taste`() {
        val taste = v(1f, 0f)
        val ranked = rankByTaste(taste, listOf("far" to v(0f, 1f), "near" to v(1f, 0.1f), "mid" to v(1f, 1f)))
        assertEquals(listOf("near", "mid", "far"), ranked.map { it.item })
    }

    @Test
    fun `lift is how far above the candidates' average a score is, in standard deviations`() {
        val ranked = rankByTaste(v(1f, 0f), listOf("a" to v(1f, 0f), "b" to v(0f, 1f)))
        // similarities 1 and 0: mean 0.5, sd 0.5
        assertEquals(1.0, ranked[0].lift, 1e-6)
        assertEquals(-1.0, ranked[1].lift, 1e-6)
    }

    @Test
    fun `equal scores have zero lift`() {
        val ranked = rankByTaste(v(1f, 0f), listOf("a" to v(1f, 0f), "b" to v(2f, 0f)))
        assertTrue(ranked.all { it.lift == 0.0 })
    }

    @Test
    fun `recommend keeps the top few that clearly stand out`() {
        val cands = listOf(
            "cafe1" to v(1f, 0f, 0f), "cafe2" to v(0.9f, 0.1f, 0f), "cafe3" to v(0.8f, 0.2f, 0f),
            "bar" to v(0f, 1f, 0f), "mart" to v(0f, 0f, 1f), "bank" to v(0f, 0.3f, 1f),
        )
        val saved = listOf(v(1f, 0f, 0f))
        val picks = recommend(saved, cands, limit = 2, minLift = 0.5)
        assertEquals(listOf("cafe1", "cafe2"), picks.map { it.item })
        assertTrue(recommend(saved, cands, limit = 5, minLift = 0.5).none { it.item in setOf("bar", "mart", "bank") })
    }

    @Test
    fun `recommend keeps both tastes of a mixed user and ignores one odd save`() {
        // Cafés on x, bars on y, one odd save (a mart) on z.
        val saved = listOf(v(1f, 0f, 0f), v(0.95f, 0.05f, 0f), v(0.9f, 0.1f, 0f), v(0f, 1f, 0f), v(0.05f, 0.95f, 0f), v(0.1f, 0.9f, 0f), v(0f, 0f, 1f))
        val cands = listOf(
            "cafe" to v(0.97f, 0.03f, 0f), "bar" to v(0.03f, 0.97f, 0f), "mart" to v(0f, 0.05f, 1f),
            "bank" to v(0.3f, 0.3f, 0.9f), "gym" to v(0.2f, 0.2f, 1f), "school" to v(0.1f, 0.3f, 0.95f),
        )
        assertEquals(setOf("cafe", "bar"), recommend(saved, cands, limit = 2).map { it.item }.toSet())
    }

    @Test
    fun `nearest scores each candidate by its closest saved place, so two tastes both count`() {
        // Saved: dessert (x) and bars (y). The mean sits between them; nearest keeps both.
        val saved = listOf(v(1f, 0f, 0f), v(0f, 1f, 0f))
        val cands = listOf("bar" to v(0f, 1f, 0f), "between" to v(0.6f, 0.6f, 0.53f), "mart" to v(0f, 0f, 1f))
        assertEquals("between", rankByTaste(tasteVector(saved)!!, cands).first().item)
        val ranked = rankByNearest(saved, cands)
        assertEquals(listOf("bar", "between", "mart"), ranked.map { it.item })
        assertEquals(1.0, ranked.first().similarity, 1e-6)
    }

    @Test
    fun `nearest k averages the k closest saves, so one odd save does not pull in its look-alikes`() {
        // Two cafés and one odd save (a mart). A mart candidate matches the odd save exactly.
        val saved = listOf(v(1f, 0f), v(0.9f, 0.1f), v(0f, 1f))
        val cands = listOf("cafe" to v(0.95f, 0.05f), "mart" to v(0f, 1f))
        assertEquals("mart", rankByNearest(saved, cands, k = 1).first().item)
        assertEquals("cafe", rankByNearest(saved, cands, k = 2).first().item)
    }

    @Test
    fun `nearest k larger than the saves uses them all`() {
        val ranked = rankByNearest(listOf(v(1f, 0f)), listOf("a" to v(1f, 0f)), k = 5)
        assertEquals(1.0, ranked.single().similarity, 1e-6)
    }

    @Test
    fun `nearest with nothing saved gives nothing`() {
        assertTrue(rankByNearest(emptyList(), listOf("a" to v(1f))).isEmpty())
    }

    @Test
    fun `text for embedding is name and category, memo only when asked`() {
        assertEquals("어니언 성수 | 카페", tasteText("어니언 성수", "카페"))
        assertEquals("어니언 성수 | 카페 | 소금빵 맛집", tasteText("어니언 성수", "카페", "소금빵 맛집"))
        assertEquals("어니언 성수 | 카페", tasteText(" 어니언 성수 ", "카페", "  "))
    }

    @Test
    fun `normalize leaves a zero vector alone`() {
        assertArrayEquals(v(0f, 0f), normalize(v(0f, 0f)))
    }
}
