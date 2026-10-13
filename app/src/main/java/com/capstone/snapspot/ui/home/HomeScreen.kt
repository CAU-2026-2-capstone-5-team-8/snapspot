package com.capstone.snapspot.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.daysLeft
import com.capstone.snapspot.core.dDay
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.core.untilLabel
import com.capstone.snapspot.ui.components.Card
import com.capstone.snapspot.ui.components.Dot
import com.capstone.snapspot.ui.components.Fact
import com.capstone.snapspot.ui.components.NearBand
import com.capstone.snapspot.ui.components.Notice
import com.capstone.snapspot.ui.components.Segmented
import com.capstone.snapspot.ui.components.Shot
import com.capstone.snapspot.ui.map.MapBranch
import com.capstone.snapspot.ui.map.MapPlace
import com.capstone.snapspot.ui.map.SpotMap
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapFonts
import com.capstone.snapspot.ui.theme.SnapType

private enum class Tab { PLACES, COUPONS }

private fun initial(s: String) = s.trim().firstOrNull()?.toString() ?: "?"

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onAdd: () -> Unit,
    onStatus: () -> Unit,
    onCoupon: (String) -> Unit,
    onAskPermissions: () -> Unit,
) {
    val places by vm.places.collectAsState()
    val coupons by vm.coupons.collectAsState()
    val here by vm.here.collectAsState()
    val win by vm.window.collectAsState()
    val perms by vm.perms.collectAsState()
    val watching by vm.watching.collectAsState()
    val focus by vm.focus.collectAsState()
    val selected by vm.selected.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.PLACES) }
    var deletingPlace by remember { mutableStateOf<Place?>(null) }
    var deletingCoupon by remember { mutableStateOf<Coupon?>(null) }
    val listState = rememberLazyListState()
    val now = System.currentTimeMillis()

    // Tapping a pin scrolls the list to that row.
    LaunchedEffect(selected, tab) {
        val id = selected ?: return@LaunchedEffect
        val i = places.indexOfFirst { it.id == id }
        if (tab == Tab.PLACES && i >= 0) listState.animateScrollToItem(i)
    }

    val mapPlaces = places.map {
        MapPlace(it.id, Coords(it.latitude, it.longitude), it.radius.toDouble(), initial(it.name), placeEnded(it, now), it.id == selected)
    }
    val mapBranches = win?.branches.orEmpty().map { (id, b) ->
        val at = Coords(b.latitude, b.longitude)
        MapBranch(id, at, initial(b.name), b.urgent, here?.let { distanceMeters(it, at) <= 200 } == true)
    }

    Box(Modifier.fillMaxSize().background(SnapColors.paper)) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
            val mapHeight = maxHeight * 0.55f
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SnapSpot", style = SnapType.logo, modifier = Modifier.weight(1f))
                    WatchBadge(perms.alertsOn, watching, onStatus)
                }
                Box(Modifier.fillMaxWidth().height(mapHeight).padding(horizontal = 16.dp)) {
                    SpotMap(
                        places = mapPlaces,
                        branches = mapBranches,
                        here = here,
                        focus = focus,
                        modifier = Modifier.fillMaxSize(),
                        onPinClick = { id ->
                            if (places.any { it.id == id }) tab = Tab.PLACES
                            vm.focusOn(id)
                        },
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(12.dp).size(44.dp).shadow(3.dp, CircleShape).clip(CircleShape)
                            .background(SnapColors.surface).clickable { vm.focusHere() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.MyLocation, contentDescription = "내 위치로", tint = SnapColors.ink) }
                }
                Spacer(Modifier.height(14.dp))
                Segmented(
                    options = Tab.entries,
                    value = tab,
                    onChange = { tab = it },
                    format = { if (it == Tab.PLACES) "장소 ${places.size}" else "쿠폰 ${coupons.count { c -> c.active }}" },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!perms.alertsOn) {
                        item {
                            Notice(
                                if (!perms.net && perms.bg) "휴대폰의 \"Google 위치 정확도\"가 꺼져 있어서 앱을 닫으면 알림이 오지 않아요."
                                else "앱을 닫아도 알림을 받으려면 알림 권한과 위치 \"항상 허용\"이 필요해요.",
                                "권한 설정하기" to onAskPermissions,
                            )
                        }
                    }
                    if (tab == Tab.PLACES) {
                        if (places.isEmpty()) item {
                            if (coupons.isEmpty()) Empty("스크린샷 한 장으로 시작해 보세요", "가 보고 싶은 곳의 스크린샷을 넣으면 근처를 지날 때 알려 드려요.")
                            else Empty("저장한 장소가 없어요", "가 보고 싶은 곳의 스크린샷을 넣으면 근처를 지날 때 알려 드려요. 쿠폰은 쿠폰 탭에 있어요.")
                        }
                        items(places, key = { it.id }) { p ->
                            val d = here?.let { distanceMeters(it, Coords(p.latitude, p.longitude)) }
                            PlaceRow(
                                p, d, ended = placeEnded(p, now), selected = p.id == selected,
                                onClick = { vm.focusOn(p.id) }, onLongClick = { deletingPlace = p },
                            )
                        }
                    } else {
                        if (coupons.isEmpty()) item { Empty("저장한 쿠폰이 없어요", "기프티콘 스크린샷을 넣으면 그 브랜드 지점 근처에서 알려 드려요.") }
                        items(coupons, key = { it.coupon.id }) { item ->
                            TicketRow(item, now, onClick = { onCoupon(item.coupon.id) }, onLongClick = { deletingCoupon = item.coupon })
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            containerColor = SnapColors.green,
            contentColor = SnapColors.surface,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(64.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "스크린샷 추가", modifier = Modifier.size(30.dp)) }
    }

    deletingPlace?.let { p ->
        ConfirmDelete("${p.name} 삭제", "이 장소의 근처 알림도 함께 꺼져요.", onConfirm = { vm.deletePlace(p); deletingPlace = null }, onDismiss = { deletingPlace = null })
    }
    deletingCoupon?.let { cp ->
        ConfirmDelete("${cp.brand} 쿠폰 삭제", "이 쿠폰의 지점 알림도 함께 꺼져요.", onConfirm = { vm.deleteCoupon(cp); deletingCoupon = null }, onDismiss = { deletingCoupon = null })
    }
}

@Composable
private fun WatchBadge(on: Boolean, watching: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(SnapColors.surface).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(if (on) SnapColors.green else SnapColors.amber)
        Text(if (on) "${watching}곳 감시 ›" else "알림 꺼짐 ›", fontFamily = SnapFonts.body, fontWeight = FontWeight.Medium, color = SnapColors.ink)
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = SnapType.title)
        Text(body, style = SnapType.meta)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(p: Place, distance: Double?, ended: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val near = distance != null && distance <= p.radius
    Card(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = BorderStroke(if (near || selected) 1.5.dp else 1.dp, if (near || selected) SnapColors.ink else SnapColors.line),
    ) {
        Column {
            if (near) NearBand("지금 근처예요")
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Shot(p.imageUri, 52.dp, 68.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, style = SnapType.title.copy(color = if (ended) SnapColors.muted else SnapColors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.category, style = SnapType.label.copy(color = SnapColors.green))
                        Fact("반경 ${p.radius}m")
                        if (ended) Fact("운영 종료") else untilLabel(p.endDate)?.let { Fact(it) }
                    }
                    if (p.note.isNotBlank()) Text(p.note, style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (distance != null && !near) Text(formatDistance(distance), style = SnapType.distance)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TicketRow(item: CouponItem, now: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = item.coupon
    val days = daysLeft(c, now)
    val near = item.active && item.nearest != null && item.nearest.second <= 200
    val border = when {
        near -> BorderStroke(1.5.dp, SnapColors.ink)
        item.urgent -> BorderStroke(1.5.dp, SnapColors.urgent)
        else -> BorderStroke(1.dp, SnapColors.line)
    }
    Card(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick), border = border) {
        Column(Modifier.background(if (item.active) SnapColors.surface else SnapColors.paper)) {
            if (near) NearBand("지금 근처: ${item.nearest!!.first}")
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Shot(c.imageUri, 48.dp, 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.brand, fontFamily = SnapFonts.display, fontSize = SnapType.title.fontSize, color = if (item.active) SnapColors.ink else SnapColors.muted, maxLines = 1)
                    Text(c.benefit, style = SnapType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            c.usedAt != null -> "사용한 쿠폰이에요"
                            !item.active -> "기간이 지났어요"
                            item.nearest != null -> "가장 가까운 지점  ${item.nearest.first}  ${formatDistance(item.nearest.second)}"
                            else -> untilLabel(c.expiresAt) ?: "유효기간 없음"
                        },
                        style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.active) dDay(days)?.let {
                    Text(it, fontFamily = SnapFonts.display, fontSize = SnapType.distance.fontSize, color = if (item.urgent) SnapColors.urgent else SnapColors.green)
                }
            }
        }
    }
}

@Composable
private fun ConfirmDelete(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = SnapType.title) },
        text = { Text(body, style = SnapType.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제", color = SnapColors.amber, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = SnapColors.ink) } },
        containerColor = SnapColors.surface,
    )
}
