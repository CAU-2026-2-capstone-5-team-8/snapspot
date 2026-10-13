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

    @Test
    fun `on screen, nothing is shown or recorded`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        var onScreen = true
        val svc = AlertService(repo, notifier, onScreen = { onScreen }) { NOW }
        repo.upsertPlace(place(id = "p"))
        repo.upsertCoupon(coupon(id = "c", brand = "스타벅스"))
        repo.saveWindow(
            WindowState(NOW, null, 90, emptyMap(), mapOf("b|${brandKey("스타벅스")}|1" to WindowBranch("스타벅스 성수점", brandKey("스타벅스"), 37.0, 127.0, false)), 0),
        )

        assertFalse(svc.onPlaceEnter("p"))
        assertFalse(svc.onBranchEnter("b|${brandKey("스타벅스")}|1"))
        assertEquals(0, notifier.shown.size)
        assertEquals(null, repo.places.value.single().lastNotifiedAt)

        assertTrue(svc.onPlaceEnter("p", force = true))

        onScreen = false
        assertTrue(svc.onBranchEnter("b|${brandKey("스타벅스")}|1"))
        assertEquals(2, notifier.shown.size)
    }

    private suspend fun seongsuBundle(repo: Repository, lastNotifiedC: Long? = null) {
        repo.upsertPlace(place(id = "a", name = "어니언 성수", latitude = 37.5446, longitude = 127.0560))
        repo.upsertPlace(place(id = "b", name = "로우키", latitude = 37.5450, longitude = 127.0565))
        repo.upsertPlace(place(id = "c", name = "무비랜드", latitude = 37.5455, longitude = 127.0570, lastNotifiedAt = lastNotifiedC))
        repo.upsertPlace(place(id = "z", name = "멀리", latitude = 37.60, longitude = 127.10))
    }

    @Test
    fun `entering a bundle place sends one bundle alert and quiets the others`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo)
        assertTrue(svc.onPlaceEnter("a"))
        assertEquals(Triple("📍 성수동에 가 볼 곳 3곳이 있어요", "어니언 성수, 로우키 외 1곳", "snapspot://bundle/a"), notifier.shown.single())
        assertTrue(repo.places.value.filter { it.id in setOf("a", "b", "c") }.all { it.lastNotifiedAt == NOW })
        assertFalse(svc.onPlaceEnter("b"))
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun `a member alerted recently holds the bundle alert back`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo, lastNotifiedC = NOW - 60_000)
        assertFalse(svc.onPlaceEnter("a"))
        assertEquals(0, notifier.shown.size)
    }

    @Test
    fun `a place outside any bundle still gets its own alert`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo)
        assertTrue(svc.onPlaceEnter("z"))
        assertEquals("snapspot://place/z", notifier.shown.single().third)
    }

    @Test
    fun `bundle alerts stay quiet on screen`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        val svc = AlertService(repo, notifier, onScreen = { true }, bundleName = { "성수동" }) { NOW }
        seongsuBundle(repo)
        assertFalse(svc.onPlaceEnter("a"))
        assertEquals(0, notifier.shown.size)
    }
}
