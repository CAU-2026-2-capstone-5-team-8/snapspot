package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A line whose words sit side by side, 10px per character, so boxes are easy to predict. */
fun ocrLine(text: String, top: Int = 0): OcrLine {
    var x = 0
    return OcrLine(
        text.split(" ").map { w ->
            OcrWord(w, Box(x, top, x + w.length * 10, top + 20)).also { x += (w.length + 1) * 10 }
        },
    )
}

class RedactTest {
    @Test
    fun `finds phone numbers in the usual Korean shapes`() {
        for (p in listOf("010-1234-5678", "010 1234 5678", "01012345678", "02-123-4567", "031-123-4567", "070-1234-5678")) {
            assertEquals(listOf(Pii.PHONE), findPii("문의 $p 로").map { it.kind }, p)
        }
    }

    @Test
    fun `finds email, resident number and card number`() {
        assertEquals(listOf(Pii.EMAIL), findPii("메일 me@cau.ac.kr").map { it.kind })
        assertEquals(listOf(Pii.RRN), findPii("030301-3123456").map { it.kind })
        assertEquals(listOf(Pii.CARD), findPii("1234-5678-9012-3456").map { it.kind })
    }

    @Test
    fun `emails the recognizer garbled are still emails`() {
        // Seen on the emulator: ML Kit read hello.onion@gmail.com as "hello.onion@gmailcom".
        for (e in listOf("hello.onion@gmailcom", "me @ cau.ac.kr", "me@cau,ac,kr", "me@cau. ac.kr")) {
            assertEquals("문의 [이메일]", redactText("문의 $e"), e)
        }
    }

    @Test
    fun `numbers the recognizer garbled are still hidden`() {
        // Dashes read as ~ or ., 0 read as O, spaces dropped or added inside the number.
        val cases = mapOf(
            "O1O-1234-5678" to Pii.PHONE, "010~1234~5678" to Pii.PHONE, "0101234 5678" to Pii.PHONE,
            "010-12 34-5678" to Pii.PHONE, "02.123~4567" to Pii.PHONE,
            "030301~3123456" to Pii.RRN, "030301.3123456" to Pii.RRN, "O30301-3123456" to Pii.RRN, "0303 01-3123456" to Pii.RRN,
            "1234~5678~9012~3456" to Pii.CARD, "1234.5678.9012.3456" to Pii.CARD, "1234 56789012 3456" to Pii.CARD,
            "9123.4567-8901" to Pii.CODE, "2O261104123456" to Pii.CODE,
        )
        for ((v, kind) in cases) {
            val hits = findPii("번호 $v 입니다")
            assertEquals(listOf(kind), hits.map { it.kind }, v)
            assertEquals("번호 [${kind.label}] 입니다", redactText("번호 $v 입니다"), v)
        }
    }

    @Test
    fun `dates, prices and times next to each other are left alone`() {
        for (t in listOf("2026.12.31 4500원", "2026.12.31 2027.01.15", "08:00 - 22:00", "아차산로9길 8 3층", "D-12 1+1", "2026-11-04 10:30")) {
            assertEquals(emptyList<Pii>(), findPii(t).map { it.kind }, t)
        }
    }

    @Test
    fun `a handle without a name before it is not an email`() {
        assertEquals(emptyList<Pii>(), findPii("@onion_seongsu 성수동").map { it.kind })
    }

    @Test
    fun `long digit runs such as barcode and order numbers are codes`() {
        assertEquals(listOf(Pii.CODE), findPii("9123 4567 8901").map { it.kind })
        assertEquals(listOf(Pii.CODE), findPii("주문번호 2610 0412 7731").map { it.kind })
    }

    @Test
    fun `dates, prices and short numbers are left alone`() {
        for (s in listOf("2026.12.31 까지", "2026-12-31", "4,700원", "B1 이벤트홀", "10:30 - 20:00", "서울 영등포구 여의대로 108")) {
            assertTrue(findPii(s).isEmpty(), s)
        }
    }

    @Test
    fun `a phone number is not also counted as a code`() {
        assertEquals(1, findPii("010 1234 5678").size)
    }

    @Test
    fun `redactText swaps each hit for its label`() {
        assertEquals("문의 [전화번호] / [이메일]", redactText("문의 010-1234-5678 / me@cau.ac.kr"))
        assertEquals("유효기간 2026.12.31 까지", redactText("유효기간 2026.12.31 까지"))
    }

    @Test
    fun `masks only the words that hold personal information, padded and kept on the image`() {
        val page = OcrPage(width = 300, height = 100, lines = listOf(ocrLine("어니언 성수"), ocrLine("문의 010-1234-5678", top = 40)))
        val boxes = maskBoxes(page, pad = 4)
        // "문의 " is 3 characters, so the phone number starts at x = 30 and is 13 characters wide.
        assertEquals(listOf(Box(26, 36, 164, 64)), boxes)
    }

    @Test
    fun `a number split across words masks every word it spans`() {
        val page = OcrPage(width = 400, height = 50, lines = listOf(ocrLine("9123 4567 8901")))
        assertEquals(3, maskBoxes(page, pad = 0).size)
    }

    @Test
    fun `barcodes are always masked, kept on the image`() {
        val page = OcrPage(
            width = 100, height = 100, lines = emptyList(),
            barcodes = listOf(Barcode(Box(0, 0, 50, 10), "912345678901"), Barcode(Box(90, 90, 100, 100))),
        )
        assertEquals(listOf(Box(0, 0, 55, 15), Box(85, 85, 100, 100)), maskBoxes(page, pad = 5))
        assertEquals(2, piiCount(page))
    }
}
