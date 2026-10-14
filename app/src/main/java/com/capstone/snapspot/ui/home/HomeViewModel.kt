package com.capstone.snapspot.ui.home

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.Bundle
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.CouponFetch
import com.capstone.snapspot.core.CouponStop
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.RouteMemo
import com.capstone.snapspot.core.RouteResult
import com.capstone.snapspot.core.Stop
import com.capstone.snapspot.core.WalkPath
import com.capstone.snapspot.core.branchSpotId
import com.capstone.snapspot.core.brandGroups
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.bundles
import com.capstone.snapspot.core.cellOf
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.fallbackBundleName
import com.capstone.snapspot.core.followBundles
import com.capstone.snapspot.core.isActive
import com.capstone.snapspot.core.isUrgent
import com.capstone.snapspot.core.model.Branch
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.nearbyBranches
import com.capstone.snapspot.core.nearbyCouponStops
import com.capstone.snapspot.core.needsMapSearch
import com.capstone.snapspot.core.shortestRoute
import com.capstone.snapspot.core.stopsOf
import com.capstone.snapspot.core.walkKey
import com.capstone.snapspot.core.walkMinutes
import com.capstone.snapspot.core.walkPath
import com.capstone.snapspot.geofence.RecheckWorker
import com.capstone.snapspot.geofence.SyncResult
import com.capstone.snapspot.geofence.hasBackgroundLocation
import com.capstone.snapspot.geofence.hasFineLocation
import com.capstone.snapspot.notify.notificationsAllowed
import com.capstone.snapspot.ui.map.MapFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** net: Android "Google Location Accuracy". When it is off, the OS drops every geofence. */
data class Perms(val notif: Boolean = false, val fg: Boolean = false, val bg: Boolean = false, val net: Boolean = true) {
    val alertsOn get() = notif && fg && bg && net
}

data class CouponItem(val coupon: Coupon, val active: Boolean, val urgent: Boolean, val nearest: Pair<String, Double>?)

/**
 * Route stops: the bundle's places in order, then one stop per coupon brand (any one of its branches).
 * [line]: where the route goes in walking order (the start, if any, then the picked option of each stop).
 */
data class BundleItem(val bundle: Bundle, val name: String, val coupons: List<CouponStop>, val route: RouteResult, val start: Coords?, val minutes: Int, val couponsFailed: Boolean) {
    val line: List<Coords> get() = listOfNotNull(start) + route.order.indices.map { i -> stopAt(route.order[i], route.picks[i]) }
    val walkKey: String get() = walkKey(start, route.order.indices.map { i -> stopAt(route.order[i], route.picks[i]) })

    private fun stopAt(stop: Int, pick: Int): Coords =
        if (stop < bundle.places.size) bundle.places[stop].let { Coords(it.latitude, it.longitude) }
        else coupons[stop - bundle.places.size].branches[pick].let { Coords(it.latitude, it.longitude) }
}

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

    /** The map follows the user until a hand moves it, a pin or row is focused, or a route opens. */
    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following.asStateFlow()
    /** Where the map's camera last came to rest, and its zoom. */
    private val _camera = MutableStateFlow<Pair<Coords, Int>?>(null)
    val camera: StateFlow<Pair<Coords, Int>?> = _camera.asStateFlow()

    /**
     * Gifticon branches the map looked up around [mapSearchAt] (by id `b|brand|kakaoId`), apart from the alert plan,
     * which only covers the user's surroundings. Uses the same 6-hour branch cache.
     */
    private val _mapBranches = MutableStateFlow<Map<String, Branch>>(emptyMap())
    val mapBranches: StateFlow<Map<String, Branch>> = _mapBranches.asStateFlow()
    private val _mapSearchAt = MutableStateFlow<Coords?>(null)
    val mapSearchAt: StateFlow<Coords?> = _mapSearchAt.asStateFlow()
    private var mapSearch: Job? = null

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

    private val _openBundle = MutableStateFlow<String?>(null)
    val openBundle: StateFlow<String?> = _openBundle.asStateFlow()

    /** Routes solved recently: position updates away from a bundle do not solve it again. */
    private val routeMemo = RouteMemo(32)

    /** Members of each bundle at the last update, to carry UI state over when a bundle's id changes. */
    private var lastMembers: Map<String, Set<String>> = emptyMap()

    /** Bundles whose "쿠폰 쓸 곳 포함" switch is on, and the branches found for them (null = the fetch failed). */
    private val _withCoupons = MutableStateFlow<Set<String>>(emptySet())
    val withCoupons: StateFlow<Set<String>> = _withCoupons.asStateFlow()
    private val couponStops = MutableStateFlow<Map<String, CouponFetch>>(emptyMap())

    /** Start from the user only when they are in the neighborhood (within 1km of a member). */
    val bundles: StateFlow<List<BundleItem>> = combine(c.repo.places, here, c.regionNames.names, _withCoupons, couponStops) { list, at, _, on, found ->
        bundles(list, System.currentTimeMillis(), at).map { b ->
            val start = at?.takeIf { a -> b.places.any { distanceMeters(a, Coords(it.latitude, it.longitude)) <= 1000 } }
            val coupons = if (b.id in on) found[b.id]?.stops.orEmpty() else emptyList()
            val stops: List<Stop> = stopsOf(b.places.map { Coords(it.latitude, it.longitude) }) +
                coupons.map { cs -> cs.branches.map { Coords(it.latitude, it.longitude) } }
            val route = routeMemo.route(stops, start, ::shortestRoute)
            val failed = b.id in on && (found[b.id]?.failed ?: 0) > 0
            BundleItem(b, c.regionNames.cached(b.center) ?: fallbackBundleName(b), coupons, route, start, walkMinutes(route.meters), failed)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Street paths of opened bundles by [BundleItem.walkKey]; a bundle without one is drawn straight. */
    private val _walks = MutableStateFlow<Map<String, WalkPath>>(emptyMap())
    val walks: StateFlow<Map<String, WalkPath>> = _walks.asStateFlow()
    /** Being asked right now. A failed walk is forgotten: asked again when the bundle is reopened or its route changes. */
    private val walksAsking = HashSet<String>()

    /** Cells already looked up this session, so a failing lookup is not retried on every position update. */
    private val namesTried = HashSet<String>()

    /** Neighborhood name of where the user is, once looked up (cached per ~1km cell, region-names.json). */
    val hereName: StateFlow<String?> = combine(here, c.regionNames.names) { at, _ -> at?.let { c.regionNames.cached(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Regions registered right now, refresh region excluded. */
    val watching: StateFlow<Int> = combine(window, perms) { w, p ->
        if (!p.net || w == null) 0 else w.byGroup.values.sum()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        refreshPerms()
        // While following, the map keeps the user in view and searches again after moving 1.5km.
        viewModelScope.launch {
            combine(here, _following) { at, on -> at.takeIf { on } }.filterNotNull().collect { at ->
                _focus.value = MapFocus(at, System.nanoTime(), zoom = null)
                if (needsMapSearch(_mapSearchAt.value, at)) searchMap(at)
            }
        }
        // A gifticon added, used or deleted changes which brands the map shows: search the same spot again.
        viewModelScope.launch {
            c.repo.coupons.map { list -> list.filter { isActive(it, System.currentTimeMillis()) }.map { brandKey(it.brand) }.toSet() }
                .distinctUntilChanged().collect { _mapSearchAt.value?.let(::searchMap) }
        }
        // Saved data or the first position changes what the window registers.
        viewModelScope.launch {
            combine(c.repo.places, c.repo.coupons, here.map { it != null }, perms.map { it.fg }) { pl, cp, known, fg ->
                listOf(pl.map { it.id to it.radius }, cp.filter { it.usedAt == null }.map { it.id }, known, fg)
            }.distinctUntilChanged().collect { if (_perms.value.fg) resync() }
        }
        // A bundle's id changes when places come and go: keep its card open and its coupon switch on.
        viewModelScope.launch {
            bundles.collect { items -> follow(items.map { it.bundle }) }
        }
        // The open bundle's walk along the streets: asked once per stop order and start (snapped to ~100m).
        viewModelScope.launch {
            combine(_openBundle, bundles) { id, items -> items.firstOrNull { it.bundle.id == id } }
                .distinctUntilChanged { a, b -> a?.walkKey == b?.walkKey }
                .collect { item ->
                    if (item == null) return@collect
                    val key = item.walkKey
                    val line = item.line
                    if (line.size < 2 || key in _walks.value || !walksAsking.add(key)) return@collect
                    launch {
                        runCatching { walkPath(c.walk, line) }
                            .onSuccess { w -> _walks.value = _walks.value + (key to w) }
                            .onFailure { Log.w("SnapSpot", "walking route failed", it) }
                        walksAsking.remove(key)
                    }
                }
        }
        // Name bundles the first time they show up (the alert path only reads this cache).
        viewModelScope.launch {
            bundles.collect { items ->
                for (i in items) {
                    if (c.regionNames.cached(i.bundle.center) != null || !namesTried.add(cellOf(i.bundle.center).key)) continue
                    c.regionNames.resolve(i.bundle.center) { c.kakao.regionName(it) }
                }
            }
        }
        // Name the cell the user is in, once per cell per session (the home headline says "지금 성수동이에요").
        viewModelScope.launch {
            here.filterNotNull().distinctUntilChangedBy { cellOf(it).key }.collect { at ->
                if (c.regionNames.cached(at) == null && namesTried.add(cellOf(at).key)) c.regionNames.resolve(at) { c.kakao.regionName(it) }
            }
        }
    }

    fun toggleBundle(id: String) {
        _following.value = false
        // The map fits the opened bundle's route into view by itself (SpotMap).
        _openBundle.value = if (_openBundle.value == id) null else id
    }

    /** From a bundle notification: open the bundle the place is in, or just the place if the bundle is gone. */
    fun openBundleFor(placeId: String) {
        val item = bundles.value.firstOrNull { b -> b.bundle.places.any { it.id == placeId } }
        if (item == null) focusOn(placeId) else if (_openBundle.value != item.bundle.id) toggleBundle(item.bundle.id)
    }

    private fun follow(now: List<Bundle>) {
        val members = now.associate { b -> b.id to b.places.map { it.id }.toSet() }
        val moved = followBundles(lastMembers, now)
        fun next(id: String) = if (members[id] != null && members[id] == lastMembers[id]) id else moved[id]
        _openBundle.value?.let { id -> if (members[id] != lastMembers[id]) _openBundle.value = next(id) }
        val on = _withCoupons.value
        val keep = on.mapNotNull { next(it) }.toSet()
        if (keep != on || on.any { members[it] != lastMembers[it] }) {
            // Branches were filtered against the old places: fetch again for bundles whose members changed.
            couponStops.value = couponStops.value.filterKeys { it in keep && members[it] == lastMembers[it] }
            _withCoupons.value = keep
            for (id in keep) if (id !in couponStops.value) fetchCoupons(id)
        }
        lastMembers = members
    }

    /** Shows the alert this bundle sends, as if entering its first stop (does not use up the real alert). */
    fun previewBundle(id: String) = viewModelScope.launch {
        val b = bundles.value.firstOrNull { it.bundle.id == id } ?: return@launch
        val first = b.route.order.firstOrNull { it < b.bundle.places.size }?.let { b.bundle.places[it].id } ?: return@launch
        c.alerts.onPlaceEnter(first, force = true)
    }

    /** Coupon stops on/off for a bundle; on fetches each brand's branches within 500m of its places (nearest 8 to the center). */
    fun toggleCoupons(bundleId: String) {
        val on = bundleId !in _withCoupons.value
        _withCoupons.value = if (on) _withCoupons.value + bundleId else _withCoupons.value - bundleId
        if (on) fetchCoupons(bundleId)
    }

    private fun fetchCoupons(bundleId: String) {
        val b = bundles.value.firstOrNull { it.bundle.id == bundleId }?.bundle ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val brands = brandGroups(c.repo.coupons.value, now).map { it.brand }
            val found = nearbyCouponStops(b.places, b.center, brands) { brand -> nearbyBranches(brand, b.center, c.kakao, c.branchCache, now).branches }
            couponStops.value = couponStops.value + (bundleId to found)
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
        _following.value = false
        val p = c.repo.places.value.firstOrNull { it.id == id }
        val b = window.value?.branches?.get(id)?.let { Coords(it.latitude, it.longitude) }
            ?: _mapBranches.value[id]?.let { Coords(it.latitude, it.longitude) }
        val at = p?.let { Coords(it.latitude, it.longitude) } ?: b ?: return
        _focus.value = MapFocus(at, System.nanoTime())
    }

    fun focusHere() {
        _selected.value = null
        _following.value = true
        here.value?.let { _focus.value = MapFocus(it, System.nanoTime(), 15) }
    }

    fun onCameraRest(at: Coords, zoom: Int, byHand: Boolean) {
        _camera.value = at to zoom
        if (byHand) _following.value = false
    }

    /** Looks up the branches of every brand with a usable gifticon around `at`, for the map. */
    fun searchMap(at: Coords) {
        _mapSearchAt.value = at
        mapSearch?.cancel()
        mapSearch = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val found = HashMap<String, Branch>()
            for (cp in c.repo.coupons.value.filter { isActive(it, now) }.distinctBy { brandKey(it.brand) }) {
                val branches = try {
                    nearbyBranches(cp.brand, at, c.kakao, c.branchCache, now).branches
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                branches.forEach { found[branchSpotId(brandKey(cp.brand), it.kakaoId)] = it }
            }
            _mapBranches.value = found
        }
    }

    fun deletePlace(p: Place) = viewModelScope.launch { c.repo.removePlace(p.id) }
    fun deleteCoupon(cp: Coupon) = viewModelScope.launch { c.repo.removeCoupon(cp.id) }
    fun markUsed(cp: Coupon) = viewModelScope.launch { c.repo.updateCoupons(listOf(cp.id)) { it.copy(usedAt = System.currentTimeMillis()) } }
    fun testPlace(p: Place) = viewModelScope.launch { c.alerts.onPlaceEnter(p.id, force = true) }

    /** Shows the alert a branch would send, using the nearest registered branch of this brand. */
    fun testCoupon(cp: Coupon) = viewModelScope.launch {
        val key = brandKey(cp.brand)
        // The nearest registered branch, like the alert the user would get walking by.
        val at = here.value
        val id = window.value?.branches?.filter { it.key.startsWith("b|$key|") }
            ?.minByOrNull { (_, b) -> at?.let { distanceMeters(it, Coords(b.latitude, b.longitude)) } ?: 0.0 }?.key
            ?: "b|$key|test"
        c.alerts.onBranchEnter(id, force = true)
    }

    fun isRefresh(id: String) = id == REFRESH_ID
}
