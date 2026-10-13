/** Coupon and place rules. Pure, so tests and the simulator can use them. */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import java.time.LocalDate
import java.time.ZoneId

/** Passing several branches of one brand in a row should alert once, not at every door. */
const val BRAND_COOLDOWN_MS = 3 * 60 * 60 * 1000L

/** Do not alert about the same place more than once in this window. */
const val PLACE_COOLDOWN_MS = 6 * 60 * 60 * 1000L

/** A coupon this close to expiry outranks other coupons for geofence slots and is badged. */
const val URGENT_DAYS = 3

private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

fun isDate(s: String?): Boolean = s != null && DATE.matches(s)

/** The last millisecond of `date` (YYYY-MM-DD) in the device time zone, or null when unparsable. */
fun endOfDay(date: String): Long? = runCatching {
    LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
}.getOrNull()

fun isActive(c: Coupon, now: Long): Boolean {
    if (c.usedAt != null) return false
    val end = c.expiresAt?.let(::endOfDay) ?: return true
    return end >= now
}

/** Whole days until expiry (0 = expires today), or null when there is no expiry date. */
fun daysLeft(c: Coupon, now: Long): Int? {
    val end = c.expiresAt?.let(::endOfDay) ?: return null
    return Math.floorDiv(end - now, 86_400_000L).toInt()
}

fun isUrgent(c: Coupon, now: Long): Boolean {
    if (!isActive(c, now)) return false
    val d = daysLeft(c, now) ?: return false
    return d <= URGENT_DAYS
}

/** Active coupons of one brand, soonest-expiring first. */
fun couponsForBrand(coupons: List<Coupon>, key: String, now: Long): List<Coupon> =
    coupons.filter { brandKey(it.brand) == key && isActive(it, now) }.sortedBy { it.expiresAt ?: "9999" }

/** True when this brand alerted recently (through any of its coupons). */
fun brandCoolingDown(coupons: List<Coupon>, key: String, now: Long): Boolean =
    coupons.any { brandKey(it.brand) == key && it.lastNotifiedAt != null && now - it.lastNotifiedAt < BRAND_COOLDOWN_MS }

fun dDay(days: Int?): String? = when {
    days == null -> null
    days < 0 -> "기간 지남"
    days == 0 -> "오늘까지"
    else -> "D-$days"
}

fun placeEnded(p: Place, now: Long): Boolean {
    val end = p.endDate?.let(::endOfDay) ?: return false
    return end < now
}
