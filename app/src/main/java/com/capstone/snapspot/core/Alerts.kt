/** What an alert says, and whether a place should alert right now. */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place

/** Why a place should NOT alert right now, or null if it should. */
fun suppressReason(p: Place, now: Long): String? {
    if (placeEnded(p, now)) return "운영 종료"
    val last = p.lastNotifiedAt
    if (last != null && now - last < PLACE_COOLDOWN_MS) return "최근에 알림"
    return null
}

fun placeAlertText(p: Place): Pair<String, String> {
    val extra = if (p.note.isNotBlank()) " · ${p.note}" else ""
    return "📍 저장해 둔 ${p.category} 근처예요" to "${p.name}$extra"
}

/** "2026-10-12" → "10월 12일까지". */
fun untilLabel(date: String?): String? {
    if (date == null || !isDate(date)) return null
    val (_, m, d) = date.split("-")
    return "${m.toInt()}월 ${d.toInt()}일까지"
}

/** `coupons` = the brand's usable coupons, soonest-expiring first. */
fun couponAlertText(branchName: String, coupons: List<Coupon>, now: Long): Pair<String, String> {
    val first = coupons.first()
    val until = untilLabel(first.expiresAt)?.let { " · $it" } ?: ""
    val urgent = if (isUrgent(first, now)) {
        val d = daysLeft(first, now) ?: 0
        if (d == 0) " · 오늘 만료" else " · D-$d"
    } else ""
    val more = if (coupons.size > 1) " 외 ${coupons.size - 1}장" else ""
    return "🎟️ $branchName 근처예요" to "${first.brand} ${first.benefit}$until$urgent$more"
}
