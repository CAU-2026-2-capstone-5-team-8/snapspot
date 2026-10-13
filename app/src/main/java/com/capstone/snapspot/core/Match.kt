/**
 * Scores Kakao Local search results against what Gemini read from the screenshot.
 * Pure functions only, so they run under plain JVM tests and the eval program.
 */
package com.capstone.snapspot.core


typealias KakaoDoc = com.capstone.snapspot.core.model.KakaoDoc

data class Extract(val name: String, val address: String, val category: String)

/** Gemini category → Kakao category_group_code values that agree with it. Missing = no opinion. */
private val CATEGORY_GROUPS = mapOf(
    "카페" to listOf("CE7"),
    "디저트" to listOf("CE7", "FD6"),
    "식당" to listOf("FD6"),
    "술집" to listOf("FD6"),
    "전시" to listOf("CT1"),
    "관광" to listOf("AT4", "CT1"),
)

private const val W_NAME = 0.6
private const val W_ADDRESS = 0.3
private const val W_CATEGORY = 0.1

private val NOISE = Regex("""[\s\p{P}\p{S}]+""")

fun normalizeName(s: String): String = s.lowercase().replace(NOISE, "").removeSuffix("점")

private fun bigrams(s: String): List<String> = (0 until s.length - 1).map { s.substring(it, it + 2) }

/** Dice coefficient on character bigrams; containment of one name in the other counts as strong. */
fun nameSimilarity(a: String, b: String): Double {
    val x = normalizeName(a)
    val y = normalizeName(b)
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    val (short, long) = if (x.length <= y.length) x to y else y to x
    if (short.length >= 2 && long.contains(short)) return 0.9
    val bx = bigrams(x)
    val by = bigrams(y)
    if (bx.isEmpty() || by.isEmpty()) return 0.0
    val pool = by.toMutableList()
    var hits = 0
    for (g in bx) if (pool.remove(g)) hits++
    return 2.0 * hits / (bx.size + by.size)
}

private val ADDRESS_NOISE = setOf("서울", "서울시", "서울특별시", "대한민국")

private fun addressTokens(address: String): List<String> =
    address.split(Regex("""[\s,]+""")).map { it.trim() }.filter { it.isNotEmpty() && it !in ADDRESS_NOISE }

/** Share of the screenshot's address tokens found in the candidate's road or lot address. null = nothing to compare. */
fun addressSimilarity(extracted: String, doc: KakaoDoc): Double? {
    val tokens = addressTokens(extracted)
    if (tokens.isEmpty()) return null
    val hay = "${doc.road_address_name} ${doc.address_name}".replace(Regex("""\s+"""), " ")
    return tokens.count { hay.contains(it) }.toDouble() / tokens.size
}

fun categorySimilarity(category: String, doc: KakaoDoc): Double? {
    val groups = CATEGORY_GROUPS[category] ?: return null
    if (doc.category_group_code.isEmpty()) return null
    return if (doc.category_group_code in groups) 1.0 else 0.0
}

/** 0..1 confidence that `doc` is the place in the screenshot. Signals with no data are left out, not counted as 0. */
fun scoreCandidate(doc: KakaoDoc, ex: Extract): Double {
    val parts = listOf(
        W_NAME to nameSimilarity(ex.name, doc.place_name),
        W_ADDRESS to addressSimilarity(ex.address, doc),
        W_CATEGORY to categorySimilarity(ex.category, doc),
    )
    var sum = 0.0
    var weight = 0.0
    for ((w, v) in parts) {
        if (v == null) continue
        sum += w * v
        weight += w
    }
    return if (weight > 0) sum / weight else 0.0
}

/**
 * "더현대 서울 B1 이벤트홀" → itself, "더현대 서울 B1", "더현대 서울", "더현대".
 * Screenshot names often carry floors/halls/event titles that Kakao does not index.
 */
fun nameVariants(name: String): List<String> {
    val words = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    return (words.size downTo 1).map { words.take(it).joinToString(" ") }
}

private val REGION_SUFFIX = Regex("""(구|동|군|읍|면)$""")

/** "서울 성동구 아차산로9길 8" → "성동구": a district/neighborhood to narrow a keyword search. */
fun regionHint(address: String): String = addressTokens(address).firstOrNull { REGION_SUFFIX.containsMatchIn(it) } ?: ""
