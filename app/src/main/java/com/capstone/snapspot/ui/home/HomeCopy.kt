package com.capstone.snapspot.ui.home

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.core.untilLabel

/** Digits read with a final consonant: 영 일 삼 육 칠 팔. */
private const val DIGITS_WITH_BATCHIM = "013678"

/** "성수동" → "성수동이에요", "을지로3가" → "을지로3가예요". */
fun copula(word: String): String {
    val last = word.lastOrNull() ?: return word
    val batchim = when (last) {
        in '가'..'힣' -> (last - '가') % 28 != 0
        in '0'..'9' -> last in DIGITS_WITH_BATCHIM
        else -> false
    }
    return word + if (batchim) "이에요" else "예요"
}

/** The sentence on top of the home sheet. `count`, when set, is drawn on lime. */
data class Headline(val kicker: String?, val before: String, val count: String? = null, val after: String? = null) {
    val text: String get() = listOfNotNull(before, count, after).joinToString(" ")
}

fun homeHeadline(region: String?, near: Int, saved: Int, alertsOn: Boolean): Headline {
    val kicker = region?.let { "지금 ${copula(it)}" }
    return when {
        saved == 0 -> Headline(kicker, "가 보고 싶은 곳을 저장해 보세요")
        near > 0 -> Headline(kicker, "근처에 가 볼 곳이", "${near}곳", "있어요")
        alertsOn -> Headline(kicker, "저장한 곳 ${saved}곳")
        else -> Headline(kicker, "저장한 곳 ${saved}곳, 알림은 꺼져 있어요")
    }
}

/** Places the user is inside the alert radius of right now. Ended pop-ups never count. */
fun nearPlaces(places: List<Place>, here: Coords?, now: Long): Set<String> {
    val at = here ?: return emptySet()
    return places.filter { !placeEnded(it, now) && distanceMeters(at, Coords(it.latitude, it.longitude)) <= it.radius }.map { it.id }.toSet()
}

/** "카페 · 10월 31일까지 · 창가 자리". */
fun placeMeta(p: Place, ended: Boolean): String =
    listOfNotNull(p.category.ifBlank { null }, if (ended) "운영 종료" else untilLabel(p.endDate), p.note.ifBlank { null }).joinToString(" · ")

/** The coupon row's second line: where to use it, or why it no longer alerts. */
fun couponMeta(item: CouponItem): String {
    val c = item.coupon
    return when {
        c.usedAt != null -> "사용한 기프티콘이에요"
        !item.active -> "기간이 지났어요"
        item.nearest != null -> "${c.benefit} · ${item.nearest.first} ${formatDistance(item.nearest.second)}"
        else -> listOfNotNull(c.benefit.ifBlank { null }, untilLabel(c.expiresAt)).joinToString(" · ").ifBlank { "유효기간 없음" }
    }
}

/** "성수동" → "성수동 코스"; the fallback "어니언 성수 근처" already reads as a name. */
fun bundleTitle(name: String): String = if (name.endsWith("근처")) name else "$name 코스"

/** Rows above the first place in the home list: headline, permission notice, bundle cards, tabs. */
fun listHeader(alertsOn: Boolean, bundles: Boolean): Int = 2 + (if (alertsOn) 0 else 1) + (if (bundles) 1 else 0)
