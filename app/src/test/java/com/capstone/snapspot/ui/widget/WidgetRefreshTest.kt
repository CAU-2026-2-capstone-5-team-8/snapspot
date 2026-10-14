package com.capstone.snapspot.ui.widget

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.coupon
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.data.WindowBranch
import com.capstone.snapspot.data.WindowState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRefreshTest {
    private fun window(vararg brands: String, at: Long = 0, here: Coords? = null) = WindowState(
        at = at, here = here, slots = brands.size, byGroup = emptyMap(),
        branches = brands.mapIndexed { i, b -> "b|$b|$i" to WindowBranch("$b $i", b, 37.0, 127.0, urgent = false) }.toMap(),
        requests = 0,
    )

    private class Rig(scope: TestScope) {
        val coupons = MutableStateFlow<List<Coupon>>(emptyList())
        val window = MutableStateFlow<WindowState?>(null)
        var calls = 0
        var failNext = false
        val job = scope.refreshWidgetsOn(coupons, window, debounceMs = 500) {
            calls++
            if (failNext) { failNext = false; error("boom") }
        }
    }

    @Test
    fun `a change in the gifticons refreshes once`() = runTest {
        val r = Rig(this)
        advanceUntilIdle()
        val start = r.calls
        r.coupons.value = listOf(coupon("a"))
        advanceUntilIdle()
        assertEquals(start + 1, r.calls)
        r.job.cancel()
    }

    @Test
    fun `the same value again does not refresh`() = runTest {
        val r = Rig(this)
        r.coupons.value = listOf(coupon("a"))
        advanceUntilIdle()
        val before = r.calls
        r.coupons.value = listOf(coupon("a"))
        r.window.value = null
        advanceUntilIdle()
        assertEquals(before, r.calls)
        r.job.cancel()
    }

    @Test
    fun `a burst of changes within the debounce becomes one refresh`() = runTest {
        val r = Rig(this)
        advanceUntilIdle()
        val before = r.calls
        r.coupons.value = listOf(coupon("a"))
        advanceTimeBy(100)
        r.coupons.value = listOf(coupon("a"), coupon("b"))
        advanceTimeBy(100)
        r.coupons.value = listOf(coupon("a"), coupon("b"), coupon("c"))
        advanceUntilIdle()
        assertEquals(before + 1, r.calls)
        r.job.cancel()
    }

    @Test
    fun `a new brand in the window refreshes but a new position does not`() = runTest {
        val r = Rig(this)
        r.window.value = window("starbucks", at = 1)
        advanceUntilIdle()
        val before = r.calls
        r.window.value = window("starbucks", at = 2, here = Coords(37.5, 127.0))
        advanceUntilIdle()
        assertEquals(before, r.calls)
        r.window.value = window("starbucks", "oliveyoung", at = 3)
        advanceUntilIdle()
        assertEquals(before + 1, r.calls)
        r.job.cancel()
    }

    @Test
    fun `a failing refresh does not stop the next one`() = runTest {
        val r = Rig(this)
        advanceUntilIdle()
        val before = r.calls
        r.failNext = true
        r.coupons.value = listOf(coupon("a"))
        advanceUntilIdle()
        r.coupons.value = listOf(coupon("a"), coupon("b"))
        advanceUntilIdle()
        assertEquals(before + 2, r.calls)
        r.job.cancel()
    }

    @Test
    fun `cancelling the job stops refreshing`() = runTest {
        val r = Rig(this)
        advanceUntilIdle()
        r.job.cancel()
        val before = r.calls
        r.coupons.value = listOf(coupon("a"))
        advanceUntilIdle()
        assertEquals(before, r.calls)
    }
}
