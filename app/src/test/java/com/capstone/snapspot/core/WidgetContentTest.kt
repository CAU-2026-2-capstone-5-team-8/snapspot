package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetContentTest {
    private fun ids(c: WidgetContent) = c.items.map { it.id }
    private fun expiring(coupons: List<com.capstone.snapspot.core.model.Coupon>, limit: Int = WIDGET_LIST_MAX) =
        widgetContent(coupons, emptySet(), NOW, WidgetKind.EXPIRING, limit)

    @Test
    fun `used and expired gifticons are left out`() {
        val c = expiring(
            listOf(
                coupon("a", expiresAt = "2026-10-20"),
                coupon("used", expiresAt = "2026-10-20", usedAt = 1),
                coupon("gone", expiresAt = "2026-10-09"),
            ),
        )
        assertEquals(listOf("a"), ids(c))
        assertEquals(1, c.total)
    }

    @Test
    fun `the last day still counts and yesterday does not`() {
        val c = expiring(listOf(coupon("today", expiresAt = "2026-10-10"), coupon("yesterday", expiresAt = "2026-10-09")))
        assertEquals(listOf("today"), ids(c))
        assertEquals("오늘까지", c.items.single().dDay)
    }

    @Test
    fun `soonest expiry first and no expiry date last`() {
        val c = expiring(
            listOf(
                coupon("none"),
                coupon("late", expiresAt = "2026-11-30"),
                coupon("soon", expiresAt = "2026-10-12"),
            ),
        )
        assertEquals(listOf("soon", "late", "none"), ids(c))
        assertNull(c.items.last().dDay)
    }

    @Test
    fun `same expiry date falls back to the one saved first`() {
        val older = coupon("older", expiresAt = "2026-10-20").copy(createdAt = 1)
        val newer = coupon("newer", expiresAt = "2026-10-20").copy(createdAt = 2)
        assertEquals(listOf("older", "newer"), ids(expiring(listOf(newer, older))))
    }

    @Test
    fun `the limit cuts the list but total counts every match`() {
        val all = (1..5).map { coupon("c$it", expiresAt = "2026-10-1$it") }
        val c = expiring(all, limit = WIDGET_COMPACT_MAX)
        assertEquals(listOf("c1", "c2"), ids(c))
        assertEquals(5, c.total)
        assertEquals(3, expiring(all).items.size)
    }

    @Test
    fun `urgent is set within three days`() {
        val c = expiring(listOf(coupon("soon", expiresAt = "2026-10-13"), coupon("far", expiresAt = "2026-10-14")))
        assertTrue(c.items.first { it.id == "soon" }.urgent)
        assertFalse(c.items.first { it.id == "far" }.urgent)
    }

    @Test
    fun `nearBranch marks gifticons whose brand is in the window, even in the expiring widget`() {
        val c = widgetContent(
            listOf(coupon("sb", brand = "스타벅스", expiresAt = "2026-10-20"), coupon("ol", brand = "올리브영", expiresAt = "2026-10-21")),
            setOf(brandKey("스타벅스")), NOW, WidgetKind.EXPIRING, WIDGET_LIST_MAX,
        )
        assertEquals(listOf("sb", "ol"), ids(c))
        assertEquals(listOf(true, false), c.items.map { it.nearBranch })
    }

    @Test
    fun `the nearby widget keeps only brands with a branch in the window`() {
        val c = widgetContent(
            listOf(
                coupon("sb1", brand = "스타벅스", expiresAt = "2026-10-25"),
                coupon("ol", brand = "올리브영", expiresAt = "2026-10-11"),
                coupon("sb2", brand = "스타벅스", expiresAt = "2026-10-15"),
            ),
            setOf(brandKey("스타벅스")), NOW, WidgetKind.NEARBY, WIDGET_LIST_MAX,
        )
        assertEquals(listOf("sb2", "sb1"), ids(c))
        assertEquals(2, c.total)
        assertTrue(c.items.all { it.nearBranch })
    }

    @Test
    fun `the nearby widget is empty without branches in the window`() {
        val c = widgetContent(listOf(coupon("sb")), emptySet(), NOW, WidgetKind.NEARBY, WIDGET_LIST_MAX)
        assertTrue(c.items.isEmpty())
        assertEquals(0, c.total)
    }

    @Test
    fun `no gifticons gives an empty result`() {
        assertEquals(WidgetContent(emptyList(), 0), expiring(emptyList()))
    }

    @Test
    fun `a used gifticon never counts as near, whatever its brand`() {
        val c = widgetContent(listOf(coupon("sb", usedAt = 1)), setOf(brandKey("스타벅스")), NOW, WidgetKind.NEARBY, WIDGET_LIST_MAX)
        assertTrue(c.items.isEmpty())
    }

    @Test
    fun `brand spelling differences still match the window brand`() {
        val c = widgetContent(
            listOf(coupon("sb", brand = "스타벅스 ")),
            setOf(brandKey("스타벅스")), NOW, WidgetKind.NEARBY, WIDGET_LIST_MAX,
        )
        assertEquals(listOf("sb"), ids(c))
    }

    @Test
    fun `an item carries what the widget draws`() {
        val item = expiring(listOf(coupon("a", brand = "메가MGC커피", benefit = "카페라떼", expiresAt = "2026-10-21"))).items.single()
        assertEquals(WidgetItem("a", "메가MGC커피", "카페라떼", "D-11", urgent = false, nearBranch = false), item)
    }
}
