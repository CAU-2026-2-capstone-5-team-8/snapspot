/** Nearby places that match the user's taste: Kakao category search around them, ranked by core/Taste.kt. */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Place

/** Below this many saved places there is no taste to speak of. */
const val MIN_TASTE_SAVES = 3

/** How far around the user to look for suggestions. */
const val TASTE_RADIUS_M = 1000

/** Kakao category groups worth visiting: café, restaurant, culture (exhibitions, theaters), sights. */
val TASTE_CATEGORIES = listOf("CE7", "FD6", "CT1", "AT4")

/** One Kakao page per category (15 places), about the candidate count measured in docs/taste.md. */
private const val TASTE_PAGE_SIZE = 15

/** A nearby place the user has not saved. [category] is Kakao's last level ("디저트카페"). */
data class Suggestion(
    val kakaoId: String,
    val name: String,
    val category: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val placeUrl: String?,
    val meters: Double,
)

/** Embeds texts, reusing what it has seen before. The app keeps vectors in a file; tests use a map. */
fun interface Embedder {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** The text embedded for a saved place: name and short category, no memo (it did not help in docs/taste.md). */
fun savedTasteText(p: Place): String = tasteText(p.name, p.category)

/**
 * Up to [limit] nearby places that stand out against the other candidates by how close they are to
 * the user's saves. Empty with fewer than [MIN_TASTE_SAVES] saves. Only names and categories are
 * embedded; addresses and coordinates stay on the phone.
 */
suspend fun suggestNearby(
    here: Coords,
    saved: List<Place>,
    api: KakaoApi,
    embedder: Embedder,
    limit: Int = 3,
): List<Suggestion> {
    if (saved.size < MIN_TASTE_SAVES) return emptyList()
    val seen = LinkedHashMap<String, KakaoDoc>()
    for (code in TASTE_CATEGORIES) {
        val docs = api.category(
            mapOf(
                "category_group_code" to code,
                "x" to here.longitude.toString(),
                "y" to here.latitude.toString(),
                "radius" to TASTE_RADIUS_M.toString(),
                "size" to TASTE_PAGE_SIZE.toString(),
                "sort" to "distance",
            ),
        )
        for (d in docs) seen.putIfAbsent(d.id, d)
    }
    val candidates = seen.values.filter { d -> !isFranchise(d) }.filter { d ->
        findDuplicatePlace(saved, d.place_name, d.id, Coords(d.y.toDouble(), d.x.toDouble())) == null
    }
    if (candidates.isEmpty()) return emptyList()

    val savedTexts = saved.map(::savedTasteText)
    val candTexts = candidates.map { tasteText(it.place_name, it.category_name) }
    val vectors = embedder.embed(savedTexts + candTexts)
    val savedVecs = vectors.take(savedTexts.size)
    val candVecs = vectors.drop(savedTexts.size)

    return recommend(savedVecs, candidates.zip(candVecs), limit = limit).map { s ->
        val d = s.item
        val at = Coords(d.y.toDouble(), d.x.toDouble())
        Suggestion(
            kakaoId = d.id,
            name = d.place_name,
            category = d.category_name.split(" > ").last().trim(),
            address = d.road_address_name.ifEmpty { d.address_name },
            latitude = at.latitude,
            longitude = at.longitude,
            placeUrl = d.place_url.ifEmpty { null },
            meters = distanceMeters(here, at),
        )
    }
}

/**
 * A franchise branch: Kakao puts the brand as the last category level ("… > 커피전문점 > 메가MGC커피")
 * and the branch name starts with it ("메가MGC커피 성수역점"). Chains are what gifticons are for; taste
 * suggestions are for places like the ones the user saved by hand.
 */
fun isFranchise(d: KakaoDoc): Boolean {
    val levels = d.category_name.split(">").map { it.trim() }.filter { it.isNotEmpty() }
    val brand = levels.lastOrNull()?.takeIf { levels.size >= 3 }?.replace(" ", "") ?: return false
    return brand.length >= 2 && d.place_name.replace(" ", "").startsWith(brand)
}

/** The place saved from a suggestion: no screenshot, the usual 200m alert radius. */
fun placeFromSuggestion(s: Suggestion, id: String, now: Long, radius: Int = 200): Place = Place(
    id = id,
    name = s.name,
    address = s.address,
    category = s.category.ifEmpty { "기타" },
    note = "",
    endDate = null,
    latitude = s.latitude,
    longitude = s.longitude,
    radius = radius,
    imageUri = null,
    kakaoId = s.kakaoId,
    placeUrl = s.placeUrl,
    createdAt = now,
)
