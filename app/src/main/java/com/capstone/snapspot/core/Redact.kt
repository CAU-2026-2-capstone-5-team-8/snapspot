/**
 * Finds personal information in what the phone read from a screenshot, so it can be hidden
 * before anything leaves the device. Pure, so it is tested on the JVM; the Android side
 * (`image/ShotReader.kt`) fills [OcrPage] from ML Kit and paints the [maskBoxes] black.
 */
package com.capstone.snapspot.core

/** A rectangle in image pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun padded(p: Int, width: Int, height: Int) = Box(
        (left - p).coerceAtLeast(0),
        (top - p).coerceAtLeast(0),
        (right + p).coerceAtMost(width),
        (bottom + p).coerceAtMost(height),
    )
}

/** One word the on-device text recognizer found, with where it is. */
data class OcrWord(val text: String, val box: Box)

/** One line of words, in reading order. */
data class OcrLine(val words: List<OcrWord>) {
    /** The words joined by single spaces; [findPii] ranges index into this. */
    val text: String get() = words.joinToString(" ") { it.text }
}

/** Everything read on the device from one screenshot. */
data class OcrPage(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>,
    val barcodes: List<Box> = emptyList(),
    val faces: List<Box> = emptyList(),
)

enum class Pii(val label: String) {
    EMAIL("이메일"),
    RRN("주민등록번호"),
    PHONE("전화번호"),
    CARD("카드번호"),

    /** 10+ digits in a row: barcode numbers, PINs, order and account numbers. */
    CODE("번호"),
}

data class PiiHit(val kind: Pii, val range: IntRange)

/** Checked in this order; a later pattern never claims characters an earlier one took. */
private val PATTERNS: List<Pair<Pii, Regex>> = listOf(
    Pii.EMAIL to Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
    Pii.RRN to Regex("""(?<!\d)\d{6}\s?-\s?[1-4]\d{6}(?!\d)"""),
    Pii.PHONE to Regex("""(?<!\d)(?:01[016789]|02|0[3-6][1-5]|070)[-.\s]?\d{3,4}[-.\s]?\d{4}(?!\d)"""),
    Pii.CARD to Regex("""(?<!\d)\d{4}[-\s]\d{4}[-\s]\d{4}[-\s]\d{4}(?!\d)"""),
    Pii.CODE to Regex("""(?<!\d)\d(?:[\s-]?\d){9,}(?!\d)"""),
)

/** Personal information in `text`, by position. Dates (2026.12.31) and prices are left alone. */
fun findPii(text: String): List<PiiHit> {
    val hits = mutableListOf<PiiHit>()
    for ((kind, re) in PATTERNS) {
        for (m in re.findAll(text)) {
            if (hits.none { it.range.first <= m.range.last && m.range.first <= it.range.last }) {
                hits += PiiHit(kind, m.range)
            }
        }
    }
    return hits.sortedBy { it.range.first }
}

/** `text` with each piece of personal information replaced by a label such as "[전화번호]". */
fun redactText(text: String): String {
    val sb = StringBuilder(text)
    for (h in findPii(text).sortedByDescending { it.range.first }) {
        sb.replace(h.range.first, h.range.last + 1, "[${h.kind.label}]")
    }
    return sb.toString()
}

/** The page's text, line by line, with personal information replaced by labels. */
fun redactedText(page: OcrPage): String = page.lines.joinToString("\n") { redactText(it.text) }

/** How many pieces of personal information the page holds (text hits, barcodes and faces). */
fun piiCount(page: OcrPage): Int = page.lines.sumOf { findPii(it.text).size } + page.barcodes.size + page.faces.size

/**
 * Rectangles to paint over before the image leaves the phone: every word that is part of a
 * [findPii] hit, every barcode and every face, each grown by `pad` pixels and kept on the image.
 */
fun maskBoxes(page: OcrPage, pad: Int = 6): List<Box> {
    val out = mutableListOf<Box>()
    for (line in page.lines) {
        val hits = findPii(line.text)
        if (hits.isEmpty()) continue
        var start = 0
        for (w in line.words) {
            val end = start + w.text.length - 1
            if (hits.any { it.range.first <= end && start <= it.range.last }) out += w.box
            start = end + 2
        }
    }
    out += page.barcodes
    out += page.faces
    return out.map { it.padded(pad, page.width, page.height) }
}
