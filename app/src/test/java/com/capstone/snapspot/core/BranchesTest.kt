package com.capstone.snapspot.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BranchesTest {
    private val here = Coords(37.5446, 127.0565)

    private fun docs(vararg nameAndCategory: Pair<String, String>) = nameAndCategory.mapIndexed { i, (name, cat) ->
        KakaoDoc(id = "$i", place_name = name, category_name = cat, road_address_name = "서울 성동구", x = "127.05", y = "37.54")
    }

    @Test
    fun `filters to real branches`() = runTest {
        val k = FakeKakao(any = docs("올리브영 성수역점" to "가정,생활 > 드럭스토어 > 올리브영", "하파크리스틴 올리브영N성수점" to "가정,생활 > 패션 > 하파크리스틴"))
        val r = nearbyBranches("올리브영", here, k, MemoryBranchCache(), now = 0)
        assertEquals(listOf("올리브영 성수역점"), r.branches.map { it.name })
        assertEquals(1, r.requests)
    }

    @Test
    fun `second lookup in the same cell is served from cache, a stale entry is refetched`() = runTest {
        val k = FakeKakao(any = docs("스타벅스 성수점" to "음식점 > 카페 > 커피전문점 > 스타벅스"))
        val cache = MemoryBranchCache()
        nearbyBranches("스타벅스", here, k, cache, now = 1_000)
        val nearby = Coords(here.latitude + 0.0001, here.longitude + 0.0001)
        assertEquals(cellOf(here).key, cellOf(nearby).key)
        assertEquals(0, nearbyBranches("스타벅스", nearby, k, cache, now = 1_000).requests)
        assertEquals(1, k.asked.size)
        assertEquals(1, nearbyBranches("스타벅스", here, k, cache, now = 1_000 + CACHE_TTL_MS + 1).requests)
    }

    @Test
    fun `stops paging when a page is not full`() = runTest {
        val k = FakeKakao(any = docs("스타벅스 성수점" to "음식점 > 카페 > 커피전문점 > 스타벅스"))
        nearbyBranches("스타벅스", here, k, MemoryBranchCache(), now = 0)
        assertEquals(1, k.asked.size)
    }

    @Test
    fun `pages up to 3 times while pages are full`() = runTest {
        val full = (0 until 15).map { "스타벅스 $it" to "음식점 > 카페 > 커피전문점 > 스타벅스" }.toTypedArray()
        val k = FakeKakao(any = docs(*full))
        val r = nearbyBranches("스타벅스", here, k, MemoryBranchCache(), now = 0)
        assertEquals(3, r.requests)
    }

    @Test
    fun `a branch repeated across pages is kept once`() = runTest {
        val full = (0 until 15).map { "스타벅스 $it" to "음식점 > 카페 > 커피전문점 > 스타벅스" }.toTypedArray()
        val r = nearbyBranches("스타벅스", here, FakeKakao(any = docs(*full)), MemoryBranchCache(), now = 0)
        assertEquals(15, r.branches.size)
    }
}
