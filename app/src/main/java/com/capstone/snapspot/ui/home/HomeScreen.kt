package com.capstone.snapspot.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.core.BRANCH_RADIUS_M
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.MAP_MIN_SEARCH_ZOOM
import com.capstone.snapspot.core.URGENT_DAYS
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.brandKeyOfRegion
import com.capstone.snapspot.core.couponsForBrand
import com.capstone.snapspot.core.dDay
import com.capstone.snapspot.core.daysLeft
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.limitBranches
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.Suggestion
import com.capstone.snapspot.core.needsMapSearch
import com.capstone.snapspot.core.placeDaysLeft
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.core.untilLabel
import com.capstone.snapspot.ui.add.PhotoSourceFab
import com.capstone.snapspot.ui.components.BigTitle
import com.capstone.snapspot.ui.components.ConfirmDelete
import com.capstone.snapspot.ui.components.Dot
import com.capstone.snapspot.ui.components.FloatingChip
import com.capstone.snapspot.ui.components.Gap
import com.capstone.snapspot.ui.components.NearChip
import com.capstone.snapspot.ui.components.Notice
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.SecondaryButton
import com.capstone.snapspot.ui.components.Segmented
import com.capstone.snapspot.ui.components.SheetHandle
import com.capstone.snapspot.ui.components.Shot
import com.capstone.snapspot.ui.map.MapBranch
import com.capstone.snapspot.ui.map.MapPlace
import com.capstone.snapspot.ui.map.SpotMap
import com.capstone.snapspot.ui.map.brandTone
import com.capstone.snapspot.ui.map.brandToneIndex
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class Tab { PLACES, COUPONS }

/** Extra bottom padding for the sheet list while collapsed: the part of the list that sits below the visible peek. */
internal fun collapsedListPadding(sheetHeight: androidx.compose.ui.unit.Dp, peek: androidx.compose.ui.unit.Dp, collapsed: Boolean) =
    if (collapsed) (sheetHeight - peek).coerceAtLeast(0.dp) else 0.dp


/** Rows of one list share a card: only the first and last rows round their outer corners. */
private fun groupShape(i: Int, n: Int): Shape {
    val r = 10.dp
    return when {
        n == 1 -> SnapShapes.card
        i == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        i == n - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/**
 * The map fills the screen; the list lives in a bottom sheet (45% when collapsed). An open bundle swaps the sheet
 * for its walking route and the top-left chip for a way back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onStatus: () -> Unit,
    onCoupon: (String) -> Unit,
    onAskPermissions: () -> Unit,
) {
    val places by vm.places.collectAsState()
    val coupons by vm.coupons.collectAsState()
    val here by vm.here.collectAsState()
    val hereName by vm.hereName.collectAsState()
    val win by vm.window.collectAsState()
    val perms by vm.perms.collectAsState()
    val watching by vm.watching.collectAsState()
    val focus by vm.focus.collectAsState()
    val selected by vm.selected.collectAsState()
    val bundleItems by vm.bundles.collectAsState()
    val openBundle by vm.openBundle.collectAsState()
    val course by vm.openCourse.collectAsState()
    val routePicker by vm.routePicker.collectAsState()
    val couponsOn by vm.withCoupons.collectAsState()
    val walks by vm.walks.collectAsState()
    val found by vm.mapBranches.collectAsState()
    val following by vm.following.collectAsState()
    val camera by vm.camera.collectAsState()
    val searchAt by vm.mapSearchAt.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    // The suggestion whose card is up, after its row was tapped.
    var suggested by remember { mutableStateOf<Suggestion?>(null) }
    val open = course
    var tab by rememberSaveable { mutableStateOf(Tab.PLACES) }
    var deletingPlace by remember { mutableStateOf<Place?>(null) }
    var deletingCoupon by remember { mutableStateOf<Coupon?>(null) }
    // The place whose card is up over the map, after its pin was tapped.
    var info by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val sheet = rememberBottomSheetScaffoldState()
    val now = System.currentTimeMillis()
    val near = nearPlaces(places, here, now)
    val header = listHeader(perms.alertsOn, bundleItems.isNotEmpty())
    val scope = rememberCoroutineScope()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Back closes an open route instead of leaving the app.
    BackHandler(enabled = openBundle != null) { vm.closeRoute() }
    BackHandler(enabled = info != null) { info = null }
    BackHandler(enabled = suggested != null) { suggested = null }
    // Row taps and bundle opens move the map: collapse the sheet so the result is visible.
    val showMap: () -> Unit = { scope.launch { sheet.bottomSheetState.partialExpand() } }

    // Tapping a pin scrolls the list to that row.
    LaunchedEffect(selected, tab, open, header) {
        val id = selected ?: return@LaunchedEffect
        val i = places.indexOfFirst { it.id == id }
        if (open == null && tab == Tab.PLACES && i >= 0) listState.animateScrollToItem(header + i)
    }

    // Each gifticon brand keeps one color on the map and in the route, in the order the brands were first saved.
    val tones = brandToneIndex(coupons.map { it.coupon }.sortedBy { it.createdAt }.map { brandKey(it.brand) })
    // An open bundle numbers its stops in walking order; coupon stops are the branch the route picked.
    val stops = open?.let { routeStops(it) }.orEmpty()
    val numberOf = stops.mapIndexedNotNull { i, st -> st.placeId?.let { it to i + 1 } }.toMap()
    // Follows the streets once TMAP answers, straight lines until then.
    val walked = open?.let { walks[it.walkKey]?.line }
    // A place whose end date has passed stays in the list but leaves the map.
    val mapPlaces = places.filter { !placeEnded(it, now) }.map {
        MapPlace(
            // Alert circles stay up while the route is still straight lines; once it follows the streets,
            // only the line and numbered stops show, since the circles would clutter it.
            it.id, Coords(it.latitude, it.longitude), if (walked != null) 0.0 else it.radius.toDouble(), it.imageUri, placeEnded(it, now),
            it.id == selected, numberOf[it.id], near = it.id in near,
        )
    } + stops.mapIndexedNotNull { i, st ->
        if (st.placeId != null) null
        else MapPlace("route-$i", st.at, 0.0, null, ended = false, selected = false, number = i + 1, couponStop = true, tone = tones[brandKey(st.brand.orEmpty())] ?: 0)
    }
    val routeLine = if (open == null) emptyList() else walked ?: open.line
    // Gifticon branches: the alert plan's around the user plus what the map looked up where it was looking,
    // drawn only near the user (while following) or the map's center, the nearest few of each brand.
    val urgentBrands = coupons.filter { it.urgent }.map { brandKey(it.coupon.brand) }.toSet()
    val branchPins: Map<String, Pair<Coords, Boolean>> =
        found.mapValues { (id, b) -> Coords(b.latitude, b.longitude) to (brandKeyOfRegion(id) in urgentBrands) } +
            win?.branches.orEmpty().mapValues { (_, b) -> Coords(b.latitude, b.longitude) to b.urgent }
    val shown = limitBranches(branchPins.mapValues { it.value.first }, if (following) here else camera?.first ?: here)
    val mapBranches = branchPins.filterKeys { it in shown }.map { (id, v) ->
        MapBranch(id, v.first, v.second, here?.let { distanceMeters(it, v.first) <= BRANCH_RADIUS_M } == true, tones[brandKeyOfRegion(id)] ?: 0)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(SnapColors.bg)) {
        val peek = maxHeight * 0.45f
        val sheetHeight = maxHeight * 0.88f
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = peek,
            sheetShape = SnapShapes.sheet,
            sheetContainerColor = SnapColors.bg,
            sheetShadowElevation = 8.dp,
            sheetDragHandle = { SheetHandle() },
            containerColor = SnapColors.bg,
            sheetContent = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(sheetHeight),
                    // Collapsed, only `peek` of the 88%-tall list shows: pad the rest so the last rows can scroll to the top.
                    contentPadding = PaddingValues(
                        start = SnapSpace.gutter, end = SnapSpace.gutter,
                        bottom = 96.dp + navBottom + collapsedListPadding(sheetHeight, peek, sheet.bottomSheetState.targetValue == SheetValue.PartiallyExpanded),
                    ),
                ) {
                    if (openBundle == null) {
                        // Header rows: keep in step with listHeader().
                        item(key = "headline") {
                            val h = homeHeadline(hereName, near.size, places.size, perms.alertsOn)
                            BigTitle(
                                h.kicker,
                                buildAnnotatedString {
                                    append(h.before)
                                    h.count?.let {
                                        append(" ")
                                        withStyle(SpanStyle(background = SnapColors.lime, color = SnapColors.limeInk)) { append(it) }
                                    }
                                    h.after?.let { append(" "); append(it) }
                                },
                                Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp),
                            )
                        }
                        if (!perms.alertsOn) item(key = "notice") {
                            Notice(
                                if (!perms.net && perms.bg) "휴대폰의 \"Google 위치 정확도\"가 꺼져 있어서 앱을 닫으면 알림이 오지 않아요."
                                else "앱을 닫아도 알림을 받으려면 알림 권한과 위치 \"항상 허용\"이 필요해요.",
                                "권한 설정하기" to onAskPermissions,
                                Modifier.padding(bottom = SnapSpace.gap),
                            )
                        }
                        if (bundleItems.isNotEmpty()) item(key = "bundles") {
                            LazyRow(Modifier.padding(bottom = SnapSpace.gap), horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap)) {
                                items(bundleItems, key = { it.bundle.id }) { b ->
                                    BundleCard(b, Modifier.fillParentMaxWidth(if (bundleItems.size == 1) 1f else 0.86f)) { info = null; vm.chooseRoutePlaces(b.bundle.id) }
                                }
                            }
                        }
                        item(key = "tabs") {
                            Segmented(
                                options = Tab.entries,
                                value = tab,
                                onChange = { tab = it },
                                format = { if (it == Tab.PLACES) "장소 ${places.size}" else "기프티콘 ${coupons.count { c -> c.active }}" },
                                modifier = Modifier.padding(bottom = SnapSpace.gap),
                            )
                        }
                        if (tab == Tab.PLACES) {
                            if (places.isEmpty()) item(key = "empty") {
                                if (coupons.isEmpty()) Empty("스크린샷 한 장으로 시작해 보세요", "가 보고 싶은 곳의 스크린샷을 넣으면 근처를 지날 때 알려 드려요.")
                                else Empty("저장한 장소가 없어요", "가 보고 싶은 곳의 스크린샷을 넣으면 근처를 지날 때 알려 드려요. 기프티콘은 기프티콘 탭에 있어요.")
                            }
                            itemsIndexed(places, key = { _, p -> p.id }) { i, p ->
                                PlaceRow(
                                    p, here?.let { distanceMeters(it, Coords(p.latitude, p.longitude)) }, near = p.id in near,
                                    ended = placeEnded(p, now), selected = p.id == selected, shape = groupShape(i, places.size),
                                    onClick = { vm.focusOn(p.id); showMap() }, onLongClick = { deletingPlace = p },
                                )
                            }
                            if (suggestions.isNotEmpty()) {
                                item(key = "taste-head") {
                                    Text(TASTE_TITLE, style = SnapType.title.copy(fontSize = 15.sp), modifier = Modifier.padding(start = 4.dp, top = SnapSpace.gutter, bottom = SnapSpace.gap))
                                }
                                itemsIndexed(suggestions, key = { _, sg -> "taste-${sg.kakaoId}" }) { i, sg ->
                                    SuggestionRow(sg, groupShape(i, suggestions.size)) { suggested = sg }
                                }
                            }
                        } else {
                            if (coupons.isEmpty()) item(key = "empty") { Empty("저장한 기프티콘이 없어요", "기프티콘 스크린샷을 넣으면 그 브랜드 지점 근처에서 알려 드려요.") }
                            itemsIndexed(coupons, key = { _, c -> c.coupon.id }) { i, item ->
                                TicketRow(item, tones[brandKey(item.coupon.brand)] ?: 0, now, groupShape(i, coupons.size), onClick = { onCoupon(item.coupon.id) }, onLongClick = { deletingCoupon = item.coupon })
                            }
                        }
                    } else if (open != null) {
                        item(key = "bundle-head") {
                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                                BigTitle("가 볼 곳 ${open.route.order.size}곳", "걸어서 ${walks[open.walkKey]?.minutes ?: open.minutes}분", Modifier.weight(1f))
                                Text("기프티콘 포함", style = SnapType.label)
                                Gap(6.dp)
                                Switch(
                                    checked = open.bundle.id in couponsOn,
                                    onCheckedChange = { vm.toggleCoupons(open.bundle.id) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SnapColors.lime, checkedTrackColor = SnapColors.ink, checkedBorderColor = SnapColors.ink,
                                        uncheckedThumbColor = SnapColors.surface, uncheckedTrackColor = SnapColors.line, uncheckedBorderColor = SnapColors.line,
                                    ),
                                )
                            }
                        }
                        item(key = "bundle-edit") {
                            Column(Modifier.padding(bottom = SnapSpace.gap), verticalArrangement = Arrangement.spacedBy(SnapSpace.gap)) {
                                SecondaryButton("코스 변경", { info = null; vm.chooseRoutePlaces(open.bundle.id) }, Modifier.fillMaxWidth())
                                Text("골라 둔 ${open.routePlaces.size}곳으로 만든 이번 코스예요.", style = SnapType.meta)
                                if (open.orderedPlaceIds.isNotEmpty()) {
                                    val names = open.orderedPlaceIds.mapNotNull { id -> open.routePlaces.firstOrNull { it.id == id }?.name }
                                    Text("장소 순서 · ${names.joinToString(" → ")}", style = SnapType.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (open.couponsLoading) item(key = "bundle-loading") {
                            Notice("이 코스에서 쓸 기프티콘 지점을 찾고 있어요.", modifier = Modifier.padding(bottom = SnapSpace.gap))
                        }
                        if (open.couponsFailed) item(key = "bundle-failed") {
                            Notice(
                                if (open.coupons.isEmpty()) "기프티콘 지점을 불러오지 못했어요" else "일부 기프티콘 지점을 불러오지 못했어요",
                                modifier = Modifier.padding(bottom = SnapSpace.gap),
                            )
                        }
                        itemsIndexed(stops, key = { i, _ -> "stop-$i" }) { i, st ->
                            val p = places.firstOrNull { it.id == st.placeId }
                            StopRow(
                                i + 1, st, p, near = p != null && p.id in near, tone = tones[brandKey(st.brand.orEmpty())] ?: 0, shape = groupShape(i, stops.size),
                                onClick = p?.let { { vm.focusOn(it.id); showMap() } },
                            )
                        }
                        item(key = "bundle-preview") {
                            Text(
                                "동네 알림 미리 보기",
                                style = SnapType.button.copy(color = SnapColors.ink2),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = SnapSpace.gap).clip(SnapShapes.card)
                                    .clickable { vm.previewBundle(open.bundle.id) }.padding(vertical = 14.dp),
                            )
                        }
                    } else {
                        item(key = "route-solving") {
                            Notice("선택한 장소로 동선을 만들고 있어요.", modifier = Modifier.padding(vertical = SnapSpace.gap))
                        }
                    }
                }
            },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                SpotMap(
                    places = mapPlaces,
                    branches = mapBranches,
                    here = here,
                    focus = focus,
                    modifier = Modifier.fillMaxSize(),
                    route = routeLine,
                    padBottom = peek,
                    onCameraRest = vm::onCameraRest,
                    onPinClick = { id ->
                        // A gifticon pin (a branch, or a coupon stop on the route) opens its brand's soonest-expiring gifticon.
                        val brand = when {
                            places.any { it.id == id } -> null
                            id.startsWith("route-") -> stops.getOrNull(id.removePrefix("route-").toIntOrNull() ?: -1)?.brand?.let(::brandKey)
                            else -> brandKeyOfRegion(id)
                        }
                        val gift = brand?.let { couponsForBrand(coupons.map { it.coupon }, it, now).firstOrNull() }
                        when {
                            gift != null -> onCoupon(gift.id)
                            places.any { it.id == id } -> { tab = Tab.PLACES; vm.focusOn(id); info = id }
                            else -> vm.focusOn(id)
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (openBundle == null) {
                        FloatingChip(Modifier.weight(1f, fill = false)) { Text("SnapSpot", style = SnapType.logo) }
                    } else {
                        FloatingChip(Modifier.weight(1f, fill = false), onClick = { vm.closeRoute() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "코스 닫기", tint = SnapColors.ink, modifier = Modifier.size(18.dp))
                            Gap(6.dp)
                            Text("내가 고른 코스", style = SnapType.title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Gap(8.dp)
                    WatchChip(perms.alertsOn, watching, onStatus)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 60.dp, end = 12.dp).size(44.dp)
                        .shadow(4.dp, SnapShapes.card).clip(SnapShapes.card).background(SnapColors.surface).clickable { vm.focusHere() },
                    contentAlignment = Alignment.Center,
                ) {
                    // Filled and blue while the map follows the user; hollow once a hand has moved it away.
                    Icon(
                        if (following) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                        contentDescription = if (following) "지도가 내 위치를 따라가는 중" else "내 위치로",
                        tint = if (following) SnapColors.me else SnapColors.ink2,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // After moving the map by hand far from the last search: look up gifticon branches there.
                val rest = camera
                if (openBundle == null && !following && rest != null && coupons.any { it.active }) {
                    val chip = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
                    if (rest.second < MAP_MIN_SEARCH_ZOOM) {
                        FloatingChip(chip) { Text("지도를 확대하면 지점을 찾을 수 있어요", style = SnapType.label) }
                    } else if (needsMapSearch(searchAt, rest.first)) {
                        FloatingChip(chip, onClick = { vm.searchMap(rest.first) }) {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = SnapColors.ink, modifier = Modifier.size(16.dp))
                            Gap(6.dp)
                            Text("이 지역 기프티콘 지점 찾기", style = SnapType.label.copy(color = SnapColors.ink))
                        }
                    }
                }
            }
        }
        if (openBundle == null) {
            PhotoSourceFab(onCamera = onCamera, onGallery = onGallery)
        }
        places.firstOrNull { it.id == info }?.let { p ->
            val bundle = bundleItems.firstOrNull { b -> b.bundle.places.any { it.id == p.id } }
            PlaceCard(
                p, now,
                onRoute = bundle?.takeIf { it.bundle.id != openBundle }?.let { b -> { info = null; vm.chooseRoutePlaces(b.bundle.id) } },
                onClose = { info = null },
            )
        }
    }

    routePicker?.let { request ->
        val source = bundleItems.firstOrNull { it.bundle.id == request.bundleId }
        if (source != null) {
            RoutePlacePicker(
                requestId = request.token,
                places = places,
                originalIds = source.bundle.places.map { it.id }.toSet(),
                initialIds = request.initialIds,
                initialOrderedPlaceIds = request.initialOrderedPlaceIds,
                here = here,
                now = now,
                onDismiss = vm::dismissRoutePlacePicker,
                onApply = { ids ->
                    vm.applyRoutePlaces(ids).also { applied ->
                        if (applied) showMap()
                    }
                },
            )
        }
    }

    suggested?.let { sg ->
        SuggestionCard(sg, onSave = { vm.saveSuggestion(sg); suggested = null }, onClose = { suggested = null })
    }

    deletingPlace?.let { p ->
        ConfirmDelete("${p.name} 삭제", "이 장소의 근처 알림도 함께 꺼져요.", onConfirm = { vm.deletePlace(p); deletingPlace = null }, onDismiss = { deletingPlace = null })
    }
    deletingCoupon?.let { cp ->
        ConfirmDelete("${cp.brand} 기프티콘 삭제", "이 기프티콘의 지점 알림도 함께 꺼져요.", onConfirm = { vm.deleteCoupon(cp); deletingCoupon = null }, onDismiss = { deletingCoupon = null })
    }
}

/** Dragged down this far, the place card closes. */
private val CARD_DISMISS = 96.dp

/**
 * A place's card over the map: its screenshot, category, end date and note. A light shade keeps the tapped pin
 * visible; tapping outside or dragging the card down closes it.
 */
@Composable
private fun PlaceCard(p: Place, now: Long, onRoute: (() -> Unit)?, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    val ended = placeEnded(p, now)
    val dismissPx = with(LocalDensity.current) { CARD_DISMISS.toPx() }
    var drag by remember(p.id) { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize().background(SnapColors.ink.copy(alpha = 0.15f)).clickable(onClickLabel = "닫기", onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.offset { IntOffset(0, drag.roundToInt()) }
                .draggable(
                    rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) },
                    Orientation.Vertical,
                    onDragStopped = { if (drag > dismissPx) onClose() else drag = 0f },
                )
                .shadow(8.dp, SnapShapes.sheet).fillMaxWidth().clip(SnapShapes.sheet).background(SnapColors.surface)
                .clickable(enabled = false, onClick = {}).navigationBarsPadding().padding(start = SnapSpace.gutter, end = SnapSpace.gutter, bottom = SnapSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(SnapSpace.gutter),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
            Row {
                Shot(p.imageUri, 72.dp, 96.dp, modifier = if (ended) Modifier.alpha(0.5f) else Modifier)
                Gap(SnapSpace.card)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = SnapType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (p.category.isNotBlank()) Text(p.category, style = SnapType.label)
                    (if (ended) "운영 종료" else untilLabel(p.endDate))?.let { end ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(end, style = SnapType.body.copy(color = SnapColors.ink2))
                            placeDaysLeft(p, now)?.let { d ->
                                Gap(6.dp)
                                Text(dDay(d).orEmpty(), style = SnapType.label.copy(color = if (d <= URGENT_DAYS) SnapColors.urgent else SnapColors.ink))
                            }
                        }
                    }
                    p.note.trim().ifEmpty { null }?.let {
                        Text(it, style = SnapType.body.copy(fontSize = 14.sp, color = SnapColors.ink2), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap)) {
                p.placeUrl?.let { url -> SecondaryButton("카카오맵에서 보기", { runCatching { uri.openUri(url) } }, Modifier.weight(1f)) }
                if (onRoute != null) PrimaryButton("코스 만들기", onRoute, Modifier.weight(1f))
                else SecondaryButton("닫기", onClose, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, shape: Shape, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SnapColors.surface).clickable(onClick = onClick)
            .padding(horizontal = SnapSpace.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = SnapType.title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(suggestionMeta(s), style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatDistance(s.meters), style = SnapType.label)
    }
}

/** A suggestion's card: what it is and where, with saving it as a place. Tapping outside closes it. */
@Composable
private fun SuggestionCard(s: Suggestion, onSave: () -> Unit, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    Box(
        Modifier.fillMaxSize().background(SnapColors.ink.copy(alpha = 0.15f)).clickable(onClickLabel = "닫기", onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.shadow(8.dp, SnapShapes.sheet).fillMaxWidth().clip(SnapShapes.sheet).background(SnapColors.surface)
                .clickable(enabled = false, onClick = {}).navigationBarsPadding().padding(start = SnapSpace.gutter, end = SnapSpace.gutter, bottom = SnapSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(SnapSpace.gutter),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.name, style = SnapType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(suggestionMeta(s), style = SnapType.label)
                if (s.address.isNotBlank()) Text(s.address, style = SnapType.body.copy(fontSize = 14.sp, color = SnapColors.ink2))
                Text(TASTE_WHY, style = SnapType.meta)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap)) {
                s.placeUrl?.let { url -> SecondaryButton("카카오맵에서 보기", { runCatching { uri.openUri(url) } }, Modifier.weight(1f)) }
                PrimaryButton("저장하기", onSave, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WatchChip(on: Boolean, watching: Int, onClick: () -> Unit) {
    FloatingChip(onClick = onClick) {
        Dot(if (on) SnapColors.limeDeep else SnapColors.urgent)
        Gap(6.dp)
        Text(if (on) "알림 ${watching}곳 ›" else "알림 꺼짐 ›", style = SnapType.label.copy(color = SnapColors.ink), maxLines = 1)
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = SnapType.title)
        Text(body, style = SnapType.meta)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    p: Place, distance: Double?, near: Boolean, ended: Boolean, selected: Boolean, shape: Shape,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) SnapColors.line else SnapColors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = SnapSpace.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Shot(p.imageUri, 40.dp, 40.dp, modifier = if (ended) Modifier.alpha(0.5f) else Modifier)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = SnapType.title.copy(fontSize = 15.sp, color = if (ended) SnapColors.muted else SnapColors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(placeMeta(p, ended), style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            near -> NearChip()
            distance != null -> Text(formatDistance(distance), style = SnapType.label)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
/** `tone`: the brand's color on the map, shown as a small square before the name so the gift pins read as a legend. */
private fun TicketRow(item: CouponItem, tone: Int, now: Long, shape: Shape, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = item.coupon
    val near = item.active && item.nearest != null && item.nearest.second <= BRANCH_RADIUS_M
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SnapColors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = SnapSpace.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Shot(c.imageUri, 40.dp, 40.dp, modifier = if (item.active) Modifier else Modifier.alpha(0.5f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (item.active) brandTone(tone) else SnapColors.line))
                Text(c.brand, style = SnapType.title.copy(fontSize = 15.sp, color = if (item.active) SnapColors.ink else SnapColors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(couponMeta(item), style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            near -> NearChip()
            item.active -> dDay(daysLeft(c, now))?.let {
                Text(it, style = SnapType.label.copy(fontWeight = FontWeight.Bold, color = if (item.urgent) SnapColors.urgent else SnapColors.ink2))
            }
        }
    }
}

/** One stop of an open bundle's route: a saved place, or the branch picked for a coupon brand. */
private class RouteStop(val placeId: String?, val name: String, val at: Coords, val brand: String? = null)

private fun routeStops(b: BundleItem): List<RouteStop> = b.route.order.indices.map { i ->
    val s = b.route.order[i]
    val k = b.route.picks[i]
    if (s < b.routePlaces.size) {
        val p = b.routePlaces[s]
        RouteStop(p.id, p.name, Coords(p.latitude, p.longitude))
    } else {
        val cs = b.coupons[s - b.routePlaces.size]
        val br = cs.branches[k]
        RouteStop(null, br.name, Coords(br.latitude, br.longitude), cs.brand)
    }
}

@Composable
private fun StopRow(number: Int, stop: RouteStop, place: Place?, near: Boolean, tone: Int, shape: Shape, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SnapColors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = SnapSpace.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StopNumber(number, couponTone = if (place == null) tone else null)
        if (place != null) Shot(place.imageUri, 40.dp, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stop.name, style = SnapType.title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (place != null) place.category else "기프티콘 · ${stop.brand.orEmpty()}", style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (near) NearChip()
    }
}

/** Route numbers match the map: ink circle with a lime digit for places, a square in the brand's color for coupon branches. */
@Composable
private fun StopNumber(n: Int, couponTone: Int?) {
    val shape = if (couponTone != null) RoundedCornerShape(4.dp) else CircleShape
    Box(
        Modifier.size(24.dp).clip(shape).background(if (couponTone != null) brandTone(couponTone) else SnapColors.ink),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = SnapType.meta.copy(fontWeight = FontWeight.Bold, color = if (couponTone != null) SnapColors.surface else SnapColors.lime))
    }
}

@Composable
private fun BundleCard(b: BundleItem, modifier: Modifier, onOpen: () -> Unit) {
    Row(
        modifier.clip(SnapShapes.card).background(SnapColors.surface).clickable(onClick = onOpen).padding(SnapSpace.card),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${b.name}에 저장한 ${b.bundle.places.size}곳", style = SnapType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("오늘 갈 곳을 골라보세요", style = SnapType.meta, maxLines = 1)
        }
        Text(
            "코스 만들기",
            style = SnapType.meta.copy(fontWeight = FontWeight.ExtraBold, color = SnapColors.lime),
            modifier = Modifier.clip(SnapShapes.small).background(SnapColors.ink).padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
