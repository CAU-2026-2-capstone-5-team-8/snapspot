/** The add flow's decisions, without Android: where the pin goes and what gets saved. */
package com.capstone.snapspot.ui.add

import com.capstone.snapspot.core.Candidate
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.Extract
import com.capstone.snapspot.core.KakaoApi
import com.capstone.snapspot.core.isDate
import com.capstone.snapspot.core.model.Branch
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.searchPlace
import kotlin.coroutines.cancellation.CancellationException

val RADII = listOf(100, 200, 300, 500)

/** Below this, the best Kakao hit is offered as a choice but the device geocoder places the pin. */
const val MIN_AUTO_MATCH = 0.4

/** reading: Gemini is reading the screenshot. locating: searching maps. ready: the user confirms. */
enum class Stage { READING, LOCATING, READY }

data class Draft(
    val stage: Stage,
    /** "place" or "coupon". */
    val kind: String,
    val imageUri: String,
    val brand: String = "",
    val benefit: String = "",
    val name: String = "",
    val address: String = "",
    val category: String = "",
    val note: String = "",
    val endDate: String = "",
    val coords: Coords? = null,
    val isPlace: Boolean = true,
    /** Coupons only: branches of the brand near the user, for a preview before saving. */
    val branches: List<Branch>? = null,
    /** Kakao result the pin came from, if any. */
    val matched: Candidate? = null,
    val candidates: List<Candidate> = emptyList(),
    val radius: Int = 200,
    /** Why reading failed; shown when this screenshot's turn comes, not while another is on screen. */
    val readError: String? = null,
)

/** At most this many screenshots at once: each one is a Gemini call. */
const val MAX_BATCH = 10

/** Screenshots confirmed one at a time, in order. Ones after the current are read ahead in the background. */
data class Batch(
    val drafts: List<Draft>,
    val index: Int = 0,
    val places: Int = 0,
    val coupons: Int = 0,
    /** Tells a late background read which batch it belongs to. */
    val id: Long = 0,
) {
    val current: Draft? get() = drafts.getOrNull(index)
    val total: Int get() = drafts.size
    /** 1-based, for "2 / 5". */
    val position: Int get() = index + 1
    val done: Boolean get() = index >= drafts.size

    /** Move on after saving a "place" or "coupon", or skipping (null). */
    fun next(saved: String?) = copy(
        index = index + 1,
        places = places + if (saved == "place") 1 else 0,
        coupons = coupons + if (saved == "coupon") 1 else 0,
    )

    fun updateAt(i: Int, f: (Draft) -> Draft): Batch =
        if (i !in drafts.indices) this else copy(drafts = drafts.toMutableList().also { it[i] = f(it[i]) })
}

fun batchSummary(b: Batch): String {
    val parts = listOfNotNull(
        "장소 ${b.places}곳".takeIf { b.places > 0 },
        "쿠폰 ${b.coupons}장".takeIf { b.coupons > 0 },
    )
    return if (parts.isEmpty()) "저장한 것이 없어요" else parts.joinToString(", ") + "을 저장했어요"
}

data class Located(val coords: Coords?, val matched: Candidate?, val candidates: List<Candidate>)

/** Kakao Local first (handles shop names), device geocoder as fallback. */
suspend fun locate(
    name: String,
    address: String,
    category: String,
    api: KakaoApi?,
    geocode: suspend (String, String) -> Coords?,
): Located {
    var candidates = emptyList<Candidate>()
    if (api != null && (name.isNotBlank() || address.isNotBlank())) {
        candidates = try {
            searchPlace(Extract(name, address, category), api).take(4)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    val best = candidates.firstOrNull()
    if (best != null && best.score >= MIN_AUTO_MATCH) {
        return Located(Coords(best.latitude, best.longitude), best, candidates)
    }
    return Located(geocode(name, address), null, candidates)
}

fun buildPlace(d: Draft, id: String, now: Long): Place? {
    val c = d.coords ?: return null
    return Place(
        id = id,
        name = d.name.trim().ifEmpty { "이름 없는 장소" },
        address = d.address.trim(),
        category = d.category.trim().ifEmpty { "기타" },
        note = d.note.trim(),
        endDate = d.endDate.trim().takeIf { isDate(it) },
        latitude = c.latitude,
        longitude = c.longitude,
        radius = d.radius,
        imageUri = d.imageUri.ifEmpty { null },
        kakaoId = d.matched?.kakaoId,
        placeUrl = d.matched?.placeUrl,
        createdAt = now,
    )
}

fun buildCoupon(d: Draft, id: String, now: Long): Coupon? {
    val brand = d.brand.trim()
    if (brand.isEmpty()) return null
    return Coupon(
        id = id,
        brand = brand,
        benefit = d.benefit.trim().ifEmpty { "쿠폰" },
        expiresAt = d.endDate.trim().takeIf { isDate(it) },
        imageUri = d.imageUri.ifEmpty { null },
        createdAt = now,
    )
}
