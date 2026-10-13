package com.capstone.snapspot.ui.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchTest {
    private fun draft(n: Int) = Draft(stage = Stage.READING, kind = "place", imageUri = "content://$n")
    private fun batch(n: Int) = Batch((1..n).map(::draft))

    @Test
    fun `starts at the first screenshot`() {
        val b = batch(3)
        assertEquals("content://1", b.current?.imageUri)
        assertEquals(1, b.position)
        assertEquals(3, b.total)
        assertFalse(b.done)
    }

    @Test
    fun `saving and skipping both move to the next screenshot and count what was saved`() {
        val b = batch(3).next("place").next(null).next("coupon")
        assertTrue(b.done)
        assertNull(b.current)
        assertEquals(1, b.places)
        assertEquals(1, b.coupons)
    }

    @Test
    fun `updates reach the screenshot they were meant for, even after moving on`() {
        val b = batch(3).next(null).updateAt(0) { it.copy(name = "late") }.updateAt(2) { it.copy(name = "ahead") }
        assertEquals("late", b.drafts[0].name)
        assertEquals("ahead", b.drafts[2].name)
        assertEquals("", b.current?.name)
    }

    @Test
    fun `an update outside the batch changes nothing`() {
        val b = batch(2)
        assertEquals(b, b.updateAt(5) { it.copy(name = "x") })
    }

    @Test
    fun `summary says what was saved`() {
        assertEquals("장소 2곳, 쿠폰 1장을 저장했어요", batchSummary(batch(3).next("place").next("place").next("coupon")))
        assertEquals("장소 1곳을 저장했어요", batchSummary(batch(2).next("place").next(null)))
        assertEquals("쿠폰 2장을 저장했어요", batchSummary(batch(2).next("coupon").next("coupon")))
        assertEquals("저장한 것이 없어요", batchSummary(batch(2).next(null).next(null)))
    }
}
