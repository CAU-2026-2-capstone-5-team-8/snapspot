package com.capstone.snapspot.notify

import com.capstone.snapspot.core.NOW
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.coupon
import com.capstone.snapspot.core.place
import com.capstone.snapspot.data.Repository
import com.capstone.snapspot.data.WindowBranch
import com.capstone.snapspot.data.WindowState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AlertServiceTest {
    @TempDir
    lateinit var dir: File

    class FakeNotifier : Notifier {
        val shown = mutableListOf<Triple<String, String, String>>()
        override fun show(id: Int, title: String, body: String, deepLink: String) {
            shown += Triple(title, body, deepLink)
        }
    }

    @Test
    fun `two enter events at once alert once`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier) { NOW }
        repo.upsertPlace(place(id = "p"))
        val results = listOf(async { svc.onPlaceEnter("p") }, async { svc.onPlaceEnter("p") }).awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1, notifier.shown.size)
        assertEquals("snapspot://place/p", notifier.shown.single().third)
        assertEquals(NOW, repo.places.value.single().lastNotifiedAt)
    }

    @Test
    fun `ended place does not alert, force still does`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier) { NOW }
        repo.upsertPlace(place(id = "p", endDate = "2026-10-01"))
        assertFalse(svc.onPlaceEnter("p"))
        assertTrue(svc.onPlaceEnter("p", force = true))
    }

    @Test
    fun `branch alert uses the window's branch name and cools the brand down`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier) { NOW }
        val key = brandKey("스타벅스")
        repo.upsertCoupon(coupon(id = "c", expiresAt = "2026-12-31"))
        val region = "b|$key|42"
        repo.saveWindow(WindowState(NOW, null, 90, emptyMap(), mapOf(region to WindowBranch("스타벅스 뚝섬역", key, 37.5, 127.0, false)), 0))
        assertTrue(svc.onBranchEnter(region))
        assertEquals("🎟️ 스타벅스 뚝섬역 근처예요", notifier.shown.single().first)
        assertEquals("snapspot://coupon/c", notifier.shown.single().third)
        assertFalse(svc.onBranchEnter("b|$key|43"))
        assertEquals(NOW, repo.coupons.value.single().lastNotifiedAt)
    }

    @Test
    fun `branch of a brand without usable coupons stays quiet`() = runTest {
        val repo = Repository(dir)
        val svc = AlertService(repo, FakeNotifier()) { NOW }
        repo.upsertCoupon(coupon(id = "c", usedAt = 1))
        assertFalse(svc.onBranchEnter("b|${brandKey("스타벅스")}|42"))
    }
}
