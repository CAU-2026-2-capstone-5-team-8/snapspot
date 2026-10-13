package com.capstone.snapspot.data

import com.capstone.snapspot.core.coupon
import com.capstone.snapspot.core.place
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RepositoryTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `saved places and coupons survive a restart, newest first`() = runTest {
        val repo = Repository(dir)
        repo.upsertPlace(place(id = "a"))
        repo.upsertPlace(place(id = "b"))
        repo.upsertCoupon(coupon(id = "c"))
        val again = Repository(dir)
        assertEquals(listOf("b", "a"), again.places.value.map { it.id })
        assertEquals(listOf("c"), again.coupons.value.map { it.id })
    }

    @Test
    fun `upsert replaces by id, remove deletes`() = runTest {
        val repo = Repository(dir)
        repo.upsertPlace(place(id = "a", name = "old"))
        repo.upsertPlace(place(id = "a", name = "new"))
        assertEquals(listOf("new"), repo.places.value.map { it.name })
        repo.removePlace("a")
        assertEquals(emptyList<String>(), Repository(dir).places.value.map { it.id })
    }

    @Test
    fun `a corrupted file reads as empty instead of crashing`() {
        File(dir, "places.json").writeText("{not json")
        assertEquals(0, Repository(dir).places.value.size)
        assertNull(Repository(dir).window.value)
    }

    @Test
    fun `updateCoupons patches only the given ids`() = runTest {
        val repo = Repository(dir)
        repo.upsertCoupon(coupon(id = "a"))
        repo.upsertCoupon(coupon(id = "b"))
        repo.updateCoupons(listOf("a")) { it.copy(usedAt = 5) }
        assertEquals(mapOf("a" to 5L, "b" to null), Repository(dir).coupons.value.associate { it.id to it.usedAt })
    }

    @Test
    fun `several places are marked notified in one write`() = runTest {
        val repo = Repository(dir)
        repo.upsertPlace(place(id = "a"))
        repo.upsertPlace(place(id = "b"))
        repo.upsertPlace(place(id = "c"))
        repo.markPlacesNotified(setOf("a", "c"), 42L)
        assertEquals(mapOf("a" to 42L, "b" to null, "c" to 42L), Repository(dir).places.value.associate { it.id to it.lastNotifiedAt })
    }
}
