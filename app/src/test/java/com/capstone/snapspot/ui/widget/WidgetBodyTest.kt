package com.capstone.snapspot.ui.widget

import com.capstone.snapspot.core.WidgetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WidgetBodyTest {
    @Test
    fun `no gifticons gives the same message for both widgets and sizes`() {
        for (kind in WidgetKind.entries) for (compact in listOf(false, true)) {
            assertEquals("저장한 기프티콘이 없어요", emptyMessage(kind, hasGifticons = false, compact = compact))
        }
    }

    @Test
    fun `expiring widget never mentions branches`() {
        assertEquals("저장한 기프티콘이 없어요", emptyMessage(WidgetKind.EXPIRING, hasGifticons = true))
    }

    @Test
    fun `nearby widget with gifticons but no branch near`() {
        assertEquals("근처에 쓸 수 있는 곳이 없어요", emptyMessage(WidgetKind.NEARBY, hasGifticons = true))
        assertEquals("근처 지점이 없어요", emptyMessage(WidgetKind.NEARBY, hasGifticons = true, compact = true))
    }
}
