package com.capstone.snapspot.geofence

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.FakeKakao
import com.capstone.snapspot.core.KakaoDoc
import com.capstone.snapspot.core.MemoryBranchCache
import com.capstone.snapspot.core.NOW
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.coupon
import com.capstone.snapspot.core.place
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowPlannerTest {
    private val here = Coords(37.5446, 127.0565)

    private fun starbucks(n: Int) = (0 until n).map { i ->
        KakaoDoc(
            id = "s$i", place_name = "스타벅스 ${i}호점", category_name = "음식점 > 카페 > 커피전문점 > 스타벅스",
            x = (here.longitude + (i + 1) * 100 / 88_000.0).toString(), y = here.latitude.toString(),
        )
    }

    @Test
    fun `registers places and the 7 nearest branches, records only registered branches`() = runTest {
        val k = FakeKakao(any = starbucks(14))
        val r = buildWindow(
            here = here,
            places = listOf(place(id = "p")),
            coupons = listOf(coupon(brand = "스타벅스", expiresAt = "2026-10-11")),
            api = k, cache = MemoryBranchCache(), now = NOW,
        )
        val ids = r.plan.regions.map { it.identifier }
        assertEquals(REFRESH_ID, ids.first())
        assertTrue("p" in ids)
        assertEquals(7, r.state.branches.size)
        assertEquals(r.state.branches.keys, ids.filter { it.startsWith("b|") }.toSet())
        assertTrue(r.state.branches.values.all { it.urgent && it.brandKey == brandKey("스타벅스") })
        assertEquals(1, r.state.requests)
    }

    @Test
    fun `without a position no branch is searched and places are still planned`() = runTest {
        val k = FakeKakao(any = starbucks(3))
        val r = buildWindow(null, listOf(place(id = "p")), listOf(coupon()), k, MemoryBranchCache(), NOW)
        assertEquals(listOf("p"), r.plan.regions.map { it.identifier })
        assertTrue(k.asked.isEmpty())
    }

    @Test
    fun `a failing brand search skips that brand only`() = runTest {
        val failing = object : com.capstone.snapspot.core.KakaoApi {
            override suspend fun keyword(params: Map<String, String>): List<KakaoDoc> =
                throw com.capstone.snapspot.core.KakaoException("카카오 검색 실패 (500): x")
            override suspend fun address(query: String) = emptyList<com.capstone.snapspot.core.AddressDoc>()
        }
        val r = buildWindow(here, listOf(place(id = "p")), listOf(coupon()), failing, MemoryBranchCache(), NOW)
        assertEquals(listOf(REFRESH_ID, "p"), r.plan.regions.map { it.identifier })
    }
}
