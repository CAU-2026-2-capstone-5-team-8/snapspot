package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BundlesTest {
    private val base = Coords(37.5446, 127.0560)

    /** A place `northM` meters north and `eastM` meters east of the base. */
    private fun at(id: String, northM: Double, eastM: Double = 0.0, name: String = id, endDate: String? = null) = place(
        id = id, name = name, endDate = endDate,
        latitude = base.latitude + northM / 111_320.0,
        longitude = base.longitude + eastM / (111_320.0 * Math.cos(Math.toRadians(base.latitude))),
    )

    @Test
    fun `places link in a chain within 700m`() {
        val ps = listOf(at("a", 0.0), at("b", 600.0), at("c", 1200.0), at("far", 5000.0), at("far2", 5300.0))
        val bs = bundles(ps, NOW)
        assertEquals(1, bs.size)
        assertEquals(setOf("a", "b", "c"), bs.single().places.map { it.id }.toSet())
        assertEquals("a", bs.single().id)
    }

    @Test
    fun `700m links, 701m does not`() {
        assertEquals(1, bundles(listOf(at("a", 0.0), at("b", 699.9), at("c", 1399.8)), NOW).size)
        assertEquals(0, bundles(listOf(at("a", 0.0), at("b", 701.0), at("c", 1402.0)), NOW).size)
    }

    @Test
    fun `fewer than 3 places are not a bundle, ended pop-ups do not count`() {
        assertEquals(0, bundles(listOf(at("a", 0.0), at("b", 100.0)), NOW).size)
        assertEquals(0, bundles(listOf(at("a", 0.0), at("b", 100.0), at("c", 200.0, endDate = "2026-01-01")), NOW).size)
    }

    @Test
    fun `nearest bundle first, or the biggest without a position`() {
        val near = listOf(at("n1", 0.0), at("n2", 100.0), at("n3", 200.0))
        val far = listOf(at("f1", 9000.0), at("f2", 9100.0), at("f3", 9200.0), at("f4", 9300.0))
        assertEquals(listOf("n1", "f1"), bundles(near + far, NOW, base).map { it.id })
        assertEquals(listOf("f1", "n1"), bundles(near + far, NOW, null).map { it.id })
    }

    @Test
    fun `bundleOf returns null when the place is no longer in a bundle`() {
        val ps = listOf(at("a", 0.0), at("b", 100.0), at("c", 200.0))
        assertEquals("a", bundleOf(ps, "c", NOW)?.id)
        assertNull(bundleOf(ps.dropLast(1), "a", NOW))
        assertNull(bundleOf(ps, "gone", NOW))
    }

    @Test
    fun `region names lose their numbers`() {
        assertEquals("성수동", regionDisplayName("성수2가1동"))
        assertEquals("성수동", regionDisplayName("성수1가2동"))
        assertEquals("역삼동", regionDisplayName("역삼1동"))
        assertEquals("종로동", regionDisplayName("종로1·2·3·4가동"))
        assertEquals("연남동", regionDisplayName("연남동"))
        assertEquals("상계동", regionDisplayName("상계3·4동"))
        assertEquals("면목동", regionDisplayName("면목3·8동"))
        assertEquals("동", regionDisplayName("동"))
    }

    @Test
    fun `alert names the entered place first, then the nearest, no route`() {
        val b = bundles(listOf(at("a", 0.0, name = "어니언 성수"), at("b", 300.0, name = "무비랜드"), at("c", 100.0, name = "로우키"), at("d", 500.0, name = "대림창고")), NOW).single()
        val (title, body) = bundleAlertText("성수동", b, "a")
        assertEquals("📍 성수동에 가 볼 곳 4곳이 있어요", title)
        assertEquals("어니언 성수, 로우키 외 2곳", body)
    }

    @Test
    fun `fallback name uses the place nearest the center`() {
        val b = bundles(listOf(at("a", 0.0, name = "가"), at("b", 100.0, name = "나"), at("c", 200.0, name = "다")), NOW).single()
        assertEquals("나 근처", fallbackBundleName(b))
    }
}
