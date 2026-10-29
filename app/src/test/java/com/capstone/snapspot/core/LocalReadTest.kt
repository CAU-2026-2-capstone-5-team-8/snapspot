package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Extracted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun page(vararg lines: String, barcodes: List<Box> = emptyList()) =
    OcrPage(1080, 2000, lines.mapIndexed { i, t -> ocrLine(t, top = i * 40) }, barcodes)

class LocalReadTest {
    /** The sample gift card in docs/samples, as ML Kit reads it. */
    private val giftCard = page(
        "11:42", "받은 선물", "교환권", "정", "중간발표 끝나고 커피 한 잔 해",
        "스타벅스", "카페 아메리카노 Tall",
        "9123 4567 8901",
        "유효기간 2026.12.31 까지 D-82", "사용처 전국 스타벅스 매장 (일부 매장 제외)", "주문번호 2610 0412 7731",
        barcodes = listOf(Box(100, 1200, 980, 1400)),
    )

    @Test
    fun `a readable gift card never leaves the phone`() {
        val plan = planRead(giftCard)
        assertEquals(ReadRoute.LOCAL, plan.route)
        val e = plan.local!!
        assertEquals("coupon", e.kind)
        assertEquals("스타벅스", e.brand)
        assertEquals("카페 아메리카노 Tall", e.benefit)
        assertEquals("2026-12-31", e.endDate)
        // The barcode number, the order number and the barcode itself.
        assertEquals(3, plan.masked)
        assertFalse(plan.text.contains("9123"))
    }

    @Test
    fun `the product under the brand wins over a message that mentions coffee`() {
        assertEquals("카페 아메리카노 Tall", findBenefit(listOf("커피 한 잔 해", "스타벅스", "카페 아메리카노 Tall"), "스타벅스"))
    }

    @Test
    fun `a gift card without a readable product goes to Gemini as text`() {
        val plan = planRead(page("교환권", "스타벅스", "유효기간 2026.12.31 까지"))
        assertEquals(ReadRoute.TEXT, plan.route)
        assertNull(plan.local)
    }

    @Test
    fun `a place post goes as redacted text`() {
        val plan = planRead(page("cafe_lover_seoul 성수 어니언 다녀왔어요", "서울 성동구 아차산로9길 8 어니언 성수", "예약 010-1234-5678"))
        assertEquals(ReadRoute.TEXT, plan.route)
        assertTrue(plan.text.contains("[전화번호]"))
        assertFalse(plan.text.contains("010-1234"))
        assertEquals(1, plan.masked)
    }

    @Test
    fun `too little text means the masked image is needed`() {
        assertEquals(ReadRoute.IMAGE, planRead(page("ONION")).route)
        assertEquals(ReadRoute.IMAGE, planRead(page()).route)
    }

    @Test
    fun `brand names match across spacing and the longest name wins`() {
        assertEquals("메가MGC커피", findBrand("메가 MGC 커피 아이스아메리카노"))
        assertEquals("스타벅스", findBrand("스타 벅스 e-Gift"))
        assertNull(findBrand("동네 카페"))
    }

    @Test
    fun `the latest date is the expiry`() {
        assertEquals("2026-12-31", findExpiry("2026.01.01 ~ 2026.12.31"))
        assertEquals("2027-03-05", findExpiry("유효기간 2027년 3월 5일까지"))
        assertNull(findExpiry("2026.13.40"))
    }

    @Test
    fun `an empty text-only answer falls back to the image`() {
        fun ex(kind: String, brand: String = "", name: String = "", isPlace: Boolean = false) =
            Extracted(kind, brand, "", isPlace, name, "", "기타", "", null)
        assertTrue(needsImage(ex("coupon")))
        assertFalse(needsImage(ex("coupon", brand = "스타벅스")))
        assertTrue(needsImage(ex("place", isPlace = true)))
        assertFalse(needsImage(ex("place", name = "어니언 성수", isPlace = true)))
    }
}
