/**
 * What the home-screen widgets list. Both widgets read the same rules from here; the screens only draw the result,
 * so changing what is shown (order, count, which gifticons) means changing this file.
 */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Coupon

/** EXPIRING: every active gifticon. NEARBY: only those whose brand has a branch in the current alert window. */
enum class WidgetKind { EXPIRING, NEARBY }

/** Rows of the medium widget. */
const val WIDGET_LIST_MAX = 3

/** Rows of the small widget (brand and D-day only, so two fit). */
const val WIDGET_COMPACT_MAX = 2

data class WidgetItem(
    val id: String,
    val brand: String,
    val benefit: String,
    /** "D-3", "오늘까지", or null without an expiry date. */
    val dDay: String?,
    val urgent: Boolean,
    /** The alert window holds a branch of this brand. Not a distance: the window is planned around where the user was. */
    val nearBranch: Boolean,
)

/** [items] is cut to the limit; [total] counts every match, for "외 N장". */
data class WidgetContent(val items: List<WidgetItem>, val total: Int)

/**
 * Active gifticons that expire soonest first (no expiry date last). [nearBrands] are the brand keys of the alert
 * window's branches (`brandKey`); NEARBY keeps only gifticons of those brands.
 */
fun widgetContent(coupons: List<Coupon>, nearBrands: Set<String>, now: Long, kind: WidgetKind, limit: Int): WidgetContent {
    val matches = coupons
        .filter { isActive(it, now) }
        .map { it to (brandKey(it.brand) in nearBrands) }
        .filter { (_, near) -> kind == WidgetKind.EXPIRING || near }
        .sortedWith(compareBy({ it.first.expiresAt ?: "9999" }, { it.first.createdAt }))
    val items = matches.take(limit).map { (c, near) ->
        WidgetItem(c.id, c.brand, c.benefit, dDay(daysLeft(c, now)), isUrgent(c, now), near)
    }
    return WidgetContent(items, matches.size)
}
