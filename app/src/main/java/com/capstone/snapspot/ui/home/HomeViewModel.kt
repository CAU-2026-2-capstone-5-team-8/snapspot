package com.capstone.snapspot.ui.home

import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.isActive
import com.capstone.snapspot.core.isUrgent
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.geofence.RecheckWorker
import com.capstone.snapspot.geofence.SyncResult
import com.capstone.snapspot.geofence.hasBackgroundLocation
import com.capstone.snapspot.geofence.hasFineLocation
import com.capstone.snapspot.notify.notificationsAllowed
import com.capstone.snapspot.ui.map.MapFocus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** net: Android "Google Location Accuracy". When it is off, the OS drops every geofence. */
data class Perms(val notif: Boolean = false, val fg: Boolean = false, val bg: Boolean = false, val net: Boolean = true) {
    val alertsOn get() = notif && fg && bg && net
}

data class CouponItem(val coupon: Coupon, val active: Boolean, val urgent: Boolean, val nearest: Pair<String, Double>?)

/** App-wide state behind the home screen (map + list), the status screen and the coupon screen. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SnapSpotApp).container
    val here: StateFlow<Coords?> = c.location.here
    val window = c.repo.window

    private val _perms = MutableStateFlow(Perms())
    val perms: StateFlow<Perms> = _perms.asStateFlow()
    private val _sync = MutableStateFlow<SyncResult?>(null)
    val sync: StateFlow<SyncResult?> = _sync.asStateFlow()
    private val _focus = MutableStateFlow<MapFocus?>(null)
    val focus: StateFlow<MapFocus?> = _focus.asStateFlow()
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    val places: StateFlow<List<Place>> = combine(c.repo.places, here) { list, at ->
        if (at == null) list else list.sortedBy { distanceMeters(at, Coords(it.latitude, it.longitude)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Urgent first, then by expiry; used or expired coupons last. */
    val coupons: StateFlow<List<CouponItem>> = combine(c.repo.coupons, window, here) { list, win, at ->
        val now = System.currentTimeMillis()
        list.map { cp ->
            val key = brandKey(cp.brand)
            val nearest = if (win == null || at == null) null else win.branches.values.filter { it.brandKey == key }
                .map { it.name to distanceMeters(at, Coords(it.latitude, it.longitude)) }.minByOrNull { it.second }
            CouponItem(cp, isActive(cp, now), isUrgent(cp, now), nearest)
        }.sortedWith(compareBy<CouponItem>({ !it.active }, { !it.urgent }, { it.coupon.expiresAt ?: "9999" }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Regions registered right now, refresh region excluded. */
    val watching: StateFlow<Int> = combine(window, perms) { w, p ->
        if (!p.net || w == null) 0 else w.byGroup.values.sum()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        refreshPerms()
        // The map starts wherever it can; once the first position arrives, go there unless something else was focused.
        viewModelScope.launch {
            val at = here.filterNotNull().first()
            if (_focus.value == null) _focus.value = MapFocus(at, System.nanoTime(), 15)
        }
        // Saved data or the first position changes what the window registers.
        viewModelScope.launch {
            combine(c.repo.places, c.repo.coupons, here.map { it != null }, perms.map { it.fg }) { pl, cp, known, fg ->
                listOf(pl.map { it.id to it.radius }, cp.filter { it.usedAt == null }.map { it.id }, known, fg)
            }.distinctUntilChanged().collect { if (_perms.value.fg) resync() }
        }
    }

    fun refreshPerms() {
        val ctx: Context = getApplication()
        val lm = ctx.getSystemService(LocationManager::class.java)
        val net = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(true)
        _perms.value = Perms(notificationsAllowed(ctx), hasFineLocation(ctx), hasBackgroundLocation(ctx), net)
        if (_perms.value.fg) c.location.start()
    }

    fun onResume() {
        c.onScreen = true
        refreshPerms()
        viewModelScope.launch {
            c.repo.reload()
            // Held alerts whose recheck chain broke (failure, process death) get picked up again.
            if (c.pending.all().isNotEmpty()) RecheckWorker.schedule(getApplication(), ExistingWorkPolicy.KEEP)
        }
    }

    /** Out of sight, geofences take over; continuous updates would only drain the battery. */
    fun onStop() {
        c.onScreen = false
        c.location.stop()
    }

    fun resync() {
        viewModelScope.launch { _sync.value = c.geofenceSync.sync(here.value) }
    }

    fun focusOn(id: String) {
        _selected.value = id
        val p = c.repo.places.value.firstOrNull { it.id == id }
        val b = window.value?.branches?.get(id)
        val at = p?.let { Coords(it.latitude, it.longitude) } ?: b?.let { Coords(it.latitude, it.longitude) } ?: return
        _focus.value = MapFocus(at, System.nanoTime())
    }

    fun focusHere() {
        _selected.value = null
        here.value?.let { _focus.value = MapFocus(it, System.nanoTime(), 15) }
    }

    fun deletePlace(p: Place) = viewModelScope.launch { c.repo.removePlace(p.id) }
    fun deleteCoupon(cp: Coupon) = viewModelScope.launch { c.repo.removeCoupon(cp.id) }
    fun markUsed(cp: Coupon) = viewModelScope.launch { c.repo.updateCoupons(listOf(cp.id)) { it.copy(usedAt = System.currentTimeMillis()) } }
    fun testPlace(p: Place) = viewModelScope.launch { c.alerts.onPlaceEnter(p.id, force = true) }

    /** Shows the alert a branch would send, using the nearest registered branch of this brand. */
    fun testCoupon(cp: Coupon) = viewModelScope.launch {
        val key = brandKey(cp.brand)
        val id = window.value?.branches?.keys?.firstOrNull { it.startsWith("b|$key|") } ?: "b|$key|test"
        c.alerts.onBranchEnter(id, force = true)
    }

    fun isRefresh(id: String) = id == REFRESH_ID
}
