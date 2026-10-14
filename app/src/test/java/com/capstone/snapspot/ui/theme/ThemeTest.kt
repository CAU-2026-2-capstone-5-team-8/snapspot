package com.capstone.snapspot.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

/** WCAG 2 relative luminance of an sRGB color. */
private fun luminance(c: Color): Double {
    fun ch(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

class ThemeTest {
    private fun assertContrast(fg: Color, bg: Color, min: Double, what: String) {
        val r = contrast(fg, bg)
        assertTrue(r >= min, "$what: ${"%.2f".format(r)}:1 < $min:1")
    }

    @Test
    fun `text colors are readable on cards`() {
        assertContrast(SnapColors.ink, SnapColors.surface, 4.5, "ink")
        assertContrast(SnapColors.ink2, SnapColors.surface, 4.5, "ink2")
        assertContrast(SnapColors.muted, SnapColors.surface, 4.5, "muted")
        assertContrast(SnapColors.danger, SnapColors.surface, 4.5, "danger")
        assertContrast(SnapColors.urgent, SnapColors.surface, 4.5, "urgent")
    }

    @Test
    fun `text colors are readable on the sheet background`() {
        assertContrast(SnapColors.ink, SnapColors.bg, 4.5, "ink on bg")
        assertContrast(SnapColors.ink2, SnapColors.bg, 4.5, "ink2 on bg")
        assertContrast(SnapColors.ink2, SnapColors.line, 4.5, "ink2 on the segmented track")
    }

    @Test
    fun `lime pairs are readable`() {
        assertContrast(SnapColors.lime, SnapColors.ink, 4.5, "lime on ink")
        assertContrast(SnapColors.limeInk, SnapColors.lime, 4.5, "limeInk on lime")
        assertContrast(SnapColors.surface, SnapColors.ink, 4.5, "white on ink")
    }

    @Test
    fun `white gift icons and numbers read on every brand color`() {
        SnapColors.brandTones.forEachIndexed { i, c -> assertContrast(SnapColors.surface, c, 3.0, "white on brand tone $i") }
    }

    @Test
    fun `map marks stand out from white`() {
        assertContrast(SnapColors.limeDeep, SnapColors.surface, 3.0, "limeDeep")
        assertContrast(SnapColors.me, SnapColors.surface, 3.0, "me")
    }
}
