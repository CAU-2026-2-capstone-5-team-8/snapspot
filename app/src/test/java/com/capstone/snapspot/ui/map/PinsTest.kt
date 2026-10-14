package com.capstone.snapspot.ui.map

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.ui.theme.SnapColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PinsTest {
    private fun place(
        near: Boolean = false, ended: Boolean = false, selected: Boolean = false, number: Int? = null, couponStop: Boolean = false,
        photo: String? = "content://shot/1",
    ) = MapPlace("p", Coords(37.5, 127.0), 200.0, photo, ended, selected, number, near, couponStop)

    @Test
    fun `a saved place shows its screenshot in a white-ringed circle, no letters`() {
        val s = placePin(place())
        assertEquals("content://shot/1", s.photo)
        assertEquals("", s.text)
        assertEquals(PinIcon.NONE, s.icon)
        assertEquals(SnapColors.surface, s.ring)
        assertEquals(PinShape.CIRCLE, s.shape)
        assertEquals(42, placePin(place(selected = true)).sizeDp)
    }

    @Test
    fun `a place without a screenshot gets a map pin glyph on ink`() {
        val s = placePin(place(photo = null))
        assertEquals(PinIcon.PLACE, s.icon)
        assertEquals(SnapColors.ink, s.fill)
        assertEquals("", s.text)
    }

    @Test
    fun `a place you are near gets a lime ring`() {
        assertEquals(SnapColors.lime, placePin(place(near = true)).ring)
    }

    @Test
    fun `an ended place is grey even when near`() {
        val s = placePin(place(ended = true, near = true))
        assertEquals(true, s.grey)
        assertEquals(SnapColors.muted, s.fill)
        assertEquals(SnapColors.surface, s.ring)
    }

    @Test
    fun `route stops show their number in lime on ink`() {
        val s = placePin(place(number = 2))
        assertEquals("2", s.text)
        assertEquals(SnapColors.ink, s.fill)
        assertEquals(SnapColors.lime, s.textColor)
    }

    @Test
    fun `coupon stops are squares in their brand's color with a white number`() {
        val s = placePin(place(number = 3, couponStop = true).copy(tone = 1))
        assertEquals(PinShape.SQUARE, s.shape)
        assertEquals(SnapColors.brandTones[1], s.fill)
        assertEquals(SnapColors.surface, s.textColor)
        assertEquals("3", s.text)
    }

    @Test
    fun `brands get colors in the order they were first saved, repeating past the palette`() {
        val keys = listOf("메가커피", "스타벅스", "메가커피") + List(SnapColors.brandTones.size) { "b$it" }
        val t = brandToneIndex(keys)
        assertEquals(0, t["메가커피"])
        assertEquals(1, t["스타벅스"])
        assertEquals(0, t["b${SnapColors.brandTones.size - 2}"])
        assertEquals(SnapColors.brandTones[0], brandTone(SnapColors.brandTones.size))
    }

    @Test
    fun `branches are gift squares in their brand's color, orange-ringed when urgent, lime-ringed when near`() {
        val b = MapBranch("b", Coords(37.5, 127.0), urgent = true, near = true, tone = 2)
        val s = branchPin(b)
        assertEquals(PinShape.SQUARE, s.shape)
        assertEquals(PinIcon.GIFT, s.icon)
        assertEquals("", s.text)
        assertEquals(SnapColors.brandTones[2], s.fill)
        assertEquals(SnapColors.surface, s.textColor)
        assertEquals(SnapColors.urgent, s.ring)
        assertEquals(SnapColors.lime, branchPin(b.copy(urgent = false)).ring)
        assertEquals(SnapColors.surface, branchPin(b.copy(urgent = false, near = false)).ring)
        assertEquals(SnapColors.brandTones[0], branchPin(b.copy(tone = 0)).fill)
    }

    @Test
    fun `radius is ink, lime-deep when near, grey when ended`() {
        assertEquals(SnapColors.ink, radiusStyle(place()).stroke.copy(alpha = 1f))
        assertEquals(SnapColors.limeDeep, radiusStyle(place(near = true)).stroke.copy(alpha = 1f))
        assertEquals(SnapColors.muted, radiusStyle(place(ended = true, near = true)).stroke.copy(alpha = 1f))
    }
}
