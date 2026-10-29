/**
 * Decides how much of a screenshot has to leave the phone. Gift cards follow a fixed layout,
 * so many are read here with rules alone; otherwise only the redacted text is sent, and the
 * (masked) image only when there is too little text to go on.
 */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Extracted

/** Where a screenshot is read, from most to least private. */
enum class ReadRoute {
    /** Rules on the phone; nothing is sent. */
    LOCAL,

    /** Only the redacted text goes to Gemini. */
    TEXT,

    /** The masked image goes to Gemini. */
    IMAGE,
}

data class ReadPlan(
    val route: ReadRoute,
    /** The redacted text (what TEXT sends). */
    val text: String,
    /** The answer when [route] is LOCAL. */
    val local: Extracted?,
    /** Pieces of personal information found (and hidden). */
    val masked: Int,
)

/** Below this many letters and digits, text alone is not enough to read the screenshot. */
const val MIN_TEXT_CHARS = 12

/** Words that mark a gift card or mobile coupon. */
private val GIFT_WORDS = listOf("유효기간", "교환권", "기프티콘", "선물", "사용처", "교환처", "쿠폰", "바코드")

/** Franchises common on Korean gift cards. Longer names first, so "메가MGC커피" beats "메가". */
val KNOWN_BRANDS: List<String> = listOf(
    "스타벅스", "투썸플레이스", "메가MGC커피", "메가커피", "컴포즈커피", "이디야커피", "이디야", "빽다방", "할리스", "폴바셋",
    "공차", "설빙", "배스킨라빈스", "던킨", "파리바게뜨", "뚜레쥬르", "올리브영", "다이소",
    "맥도날드", "버거킹", "롯데리아", "맘스터치", "KFC", "BBQ", "BHC", "교촌치킨", "굽네치킨", "도미노피자",
    "CU", "GS25", "세븐일레븐", "이마트24",
).sortedByDescending { it.length }

/** Things you get with a gift card; a line holding one is the likely benefit. */
private val BENEFIT_HINTS = listOf(
    "아메리카노", "라떼", "커피", "음료", "케이크", "아이스크림", "세트", "버거", "치킨", "피자",
    "tall", "grande", "venti", "금액권", "상품권", "할인", "원",
)

private val DATE = Regex("""(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})""")
private val LABEL_LINE = Regex("""^(유효기간|사용처|교환처|주문번호|선물\S*|보낸\S*|받은\S*|\[.+])""")

fun findBrand(text: String): String? {
    val t = normalizeName(text)
    return KNOWN_BRANDS.firstOrNull { t.contains(normalizeName(it)) }
}

/** The latest calendar date in `text` as YYYY-MM-DD (a range's end is its expiry). */
fun findExpiry(text: String): String? = DATE.findAll(text)
    .map { m -> "%s-%02d-%02d".format(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
    .filter { isDate(it) }
    .maxOrNull()

/**
 * The line that names what the card is for, or null when no line looks like one.
 * Lines right after the brand come first: the product sits under the brand on gift cards,
 * while a sender's message above it can mention coffee too.
 */
fun findBenefit(lines: List<String>, brand: String): String? {
    val key = normalizeName(brand)
    val trimmed = lines.map { it.trim() }
    val at = trimmed.indexOfFirst { normalizeName(it).contains(key) }
    val ordered = if (at < 0) trimmed else trimmed.drop(at) + trimmed.take(at)
    return ordered.firstOrNull { line ->
        val n = normalizeName(line)
        line.length in 2..30 &&
            n != key &&
            !LABEL_LINE.containsMatchIn(line) &&
            DATE.find(line) == null &&
            findPii(line).isEmpty() &&
            BENEFIT_HINTS.any { line.lowercase().contains(it) }
    }?.removePrefix(brand)?.trim()?.takeIf { it.isNotEmpty() }
}

fun planRead(page: OcrPage, minTextChars: Int = MIN_TEXT_CHARS): ReadPlan {
    val text = redactedText(page)
    val masked = piiCount(page)
    val lines = text.lines()
    val gift = GIFT_WORDS.any { text.contains(it) }
    val brand = findBrand(text)
    val expiry = findExpiry(text)
    val benefit = brand?.let { findBenefit(lines, it) }
    if (gift && brand != null && expiry != null && benefit != null) {
        val local = Extracted(
            kind = "coupon", brand = brand, benefit = benefit, isPlace = false,
            name = brand, address = "", category = "기타", note = "", endDate = expiry,
        )
        return ReadPlan(ReadRoute.LOCAL, text, local, masked)
    }
    val chars = text.count { it.isLetterOrDigit() }
    return ReadPlan(if (chars >= minTextChars) ReadRoute.TEXT else ReadRoute.IMAGE, text, null, masked)
}

/** A text-only answer this empty means the text was not enough; read the masked image instead. */
fun needsImage(e: Extracted): Boolean =
    if (e.kind == "coupon") e.brand.isBlank() else e.name.isBlank()
