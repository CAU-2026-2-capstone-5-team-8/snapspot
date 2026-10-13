package com.capstone.snapspot.notify

import com.capstone.snapspot.core.Bundle
import com.capstone.snapspot.core.PLACE_COOLDOWN_MS
import com.capstone.snapspot.core.brandCoolingDown
import com.capstone.snapspot.core.bundleAlertText
import com.capstone.snapspot.core.bundleOf
import com.capstone.snapspot.core.brandKeyOfRegion
import com.capstone.snapspot.core.couponAlertText
import com.capstone.snapspot.core.couponsForBrand
import com.capstone.snapspot.core.fallbackBundleName
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
 * Decides whether an Enter event alerts. Nothing is shown while the app is on screen (and no
 * cooldown is recorded, so the next visit still alerts). Geofence Enter events often arrive in
 * pairs, and a held alert's recheck can fire at the same time, so check → show → record runs one
 * at a time and the cooldown always sees the previous alert.
 */
class AlertService(
    private val repo: Repository,
    private val notifier: Notifier,
    /** True while the app is on screen: its own "지금 근처예요" marks do the job, no notification. */
    private val onScreen: () -> Boolean = { false },
    /** Display name of a bundle's neighborhood; must not block (cache only). */
    private val bundleName: (Bundle) -> String = ::fallbackBundleName,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    suspend fun onPlaceEnter(placeId: String, force: Boolean = false): Boolean = lock.withLock {
        if (!force && onScreen()) return@withLock false
        val places = repo.places.value
        val place = places.firstOrNull { it.id == placeId } ?: return@withLock false
        val now = clock()
        val bundle = bundleOf(places, placeId, now)
        if (bundle != null) {
            // One alert per neighborhood walk: any member alerted recently holds it back.
            if (!force && bundle.places.any { p -> p.lastNotifiedAt?.let { now - it < PLACE_COOLDOWN_MS } == true }) return@withLock false
            val (title, body) = bundleAlertText(bundleName(bundle), bundle, placeId)
            notifier.show(bundle.id.hashCode(), title, body, "snapspot://bundle/$placeId")
            // A forced preview (the bundle card's button) leaves the cooldown alone so the real alert still comes.
            if (!force) repo.markPlacesNotified(bundle.places.map { it.id }.toSet(), now)
            return@withLock true
        }
        if (!force && suppressReason(place, now) != null) return@withLock false
        val (title, body) = placeAlertText(place)
        notifier.show(place.id.hashCode(), title, body, "snapspot://place/${place.id}")
        repo.markPlaceNotified(place.id, now)
        true
    }

    /** A branch region ("b|<brandKey>|<kakaoId>") was entered: alert with the brand's coupons, once per cooldown. */
    suspend fun onBranchEnter(regionId: String, force: Boolean = false): Boolean = lock.withLock {
        if (!force && onScreen()) return@withLock false
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
