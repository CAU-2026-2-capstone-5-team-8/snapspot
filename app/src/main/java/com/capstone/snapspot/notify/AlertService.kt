package com.capstone.snapspot.notify

import com.capstone.snapspot.core.brandCoolingDown
import com.capstone.snapspot.core.brandKeyOfRegion
import com.capstone.snapspot.core.couponAlertText
import com.capstone.snapspot.core.couponsForBrand
import com.capstone.snapspot.core.placeAlertText
import com.capstone.snapspot.core.suppressReason
import com.capstone.snapspot.data.Repository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shows a notification. The app posts a real one; tests record it. */
interface Notifier {
    fun show(id: Int, title: String, body: String, deepLink: String)
}

/**
 * Decides whether an Enter event alerts. Geofence Enter events often arrive in pairs, and the
 * in-app watcher can fire at the same time, so check → show → record runs one at a time and the
 * cooldown always sees the previous alert.
 */
class AlertService(
    private val repo: Repository,
    private val notifier: Notifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    suspend fun onPlaceEnter(placeId: String, force: Boolean = false): Boolean = lock.withLock {
        val place = repo.places.value.firstOrNull { it.id == placeId } ?: return@withLock false
        val now = clock()
        if (!force && suppressReason(place, now) != null) return@withLock false
        val (title, body) = placeAlertText(place)
        notifier.show(place.id.hashCode(), title, body, "snapspot://place/${place.id}")
        repo.markPlaceNotified(place.id, now)
        true
    }

    /** A branch region ("b|<brandKey>|<kakaoId>") was entered: alert with the brand's coupons, once per cooldown. */
    suspend fun onBranchEnter(regionId: String, force: Boolean = false): Boolean = lock.withLock {
        val key = brandKeyOfRegion(regionId)
        val now = clock()
        val coupons = repo.coupons.value
        val usable = couponsForBrand(coupons, key, now)
        if (usable.isEmpty()) return@withLock false
        if (!force && brandCoolingDown(coupons, key, now)) return@withLock false
        val branchName = repo.window.value?.branches?.get(regionId)?.name ?: usable.first().brand
        val (title, body) = couponAlertText(branchName, usable, now)
        notifier.show(key.hashCode(), title, body, "snapspot://coupon/${usable.first().id}")
        repo.updateCoupons(usable.map { it.id }) { it.copy(lastNotifiedAt = now) }
        true
    }
}
