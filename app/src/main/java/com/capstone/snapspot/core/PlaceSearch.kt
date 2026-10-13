/** Screenshot text → real place, via Kakao Local search. */
package com.capstone.snapspot.core

/** Below this score the top result is shown as a guess, not a confident match. */
const val CONFIDENT = 0.7

/** An exact address is solid for location even though we cannot confirm the shop itself. */
private const val ADDRESS_SCORE = 0.65

data class Candidate(
    val kakaoId: String?,
    val name: String,
    val address: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val placeUrl: String?,
    val score: Double,
    /** "keyword" or "address". */
    val source: String,
)

/**
 * Candidates sorted by confidence (best first). Searches "district + name" and the name alone,
 * shortening the name word by word until a confident match shows up, then the address itself
 * (gives coordinates even when the shop is not listed).
 */
suspend fun searchPlace(ex: Extract, api: KakaoApi): List<Candidate> {
    val region = regionHint(ex.address)
    val seen = LinkedHashMap<String, KakaoDoc>()
    for (name in nameVariants(ex.name)) {
        val queries = listOf(listOf(region, name).filter { it.isNotEmpty() }.joinToString(" "), name).distinct()
        for (query in queries) {
            for (d in api.keyword(mapOf("query" to query, "size" to "10"))) seen.putIfAbsent(d.id, d)
        }
        // Any hit is not enough: "더현대 서울 B1" matches unrelated "B1" shops. Keep shortening until one fits.
        if (seen.values.any { scoreCandidate(it, ex) >= CONFIDENT }) break
    }
    val out = seen.values.map { d ->
        Candidate(
            kakaoId = d.id,
            name = d.place_name,
            address = d.road_address_name.ifEmpty { d.address_name },
            category = d.category_name.split(" > ").last(),
            latitude = d.y.toDouble(),
            longitude = d.x.toDouble(),
            placeUrl = d.place_url.ifEmpty { null },
            score = scoreCandidate(d, ex),
            source = "keyword",
        )
    }.toMutableList()

    if (ex.address.isNotBlank()) {
        api.address(ex.address).firstOrNull()?.let { a ->
            out += Candidate(
                kakaoId = null,
                name = ex.name.ifEmpty { a.address_name },
                address = a.road_address?.address_name ?: a.address_name,
                category = "",
                latitude = a.y.toDouble(),
                longitude = a.x.toDouble(),
                placeUrl = null,
                score = ADDRESS_SCORE,
                source = "address",
            )
        }
    }
    return out.sortedByDescending { it.score }
}
