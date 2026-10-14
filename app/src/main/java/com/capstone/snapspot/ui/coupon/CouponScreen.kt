package com.capstone.snapspot.ui.coupon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.core.BRANCH_RADIUS_M
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.dDay
import com.capstone.snapspot.core.daysLeft
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.isActive
import com.capstone.snapspot.core.isUrgent
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.swipeGroup
import com.capstone.snapspot.core.untilLabel
import com.capstone.snapspot.ui.components.BigTitle
import com.capstone.snapspot.ui.components.ConfirmDelete
import com.capstone.snapspot.ui.components.Dot
import com.capstone.snapspot.ui.components.Gap
import com.capstone.snapspot.ui.components.IconTap
import com.capstone.snapspot.ui.components.LargeShot
import com.capstone.snapspot.ui.components.NearChip
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.SecondaryButton
import com.capstone.snapspot.ui.home.HomeViewModel
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType

/**
 * The coupon `id`. When its brand has other active coupons, a swipe left and right goes through them,
 * soonest-expiring first, with a hint above the title saying which one is open ([SwipeHint]).
 */
@Composable
fun CouponScreen(vm: HomeViewModel, id: String, onClose: () -> Unit) {
    val items by vm.coupons.collectAsState()
    val all = items.map { it.coupon }
    val current = all.firstOrNull { it.id == id }
    // Settled when the screen opens, so marking one used does not reshuffle the pages under the finger.
    val group = remember(id, current != null) { current?.let { swipeGroup(all, it, System.currentTimeMillis()) }.orEmpty() }
    if (group.size < 2) {
        CouponPage(vm, current, onClose)
        return
    }
    val pager = rememberPagerState(initialPage = group.indexOfFirst { it.id == id }.coerceAtLeast(0)) { group.size }
    HorizontalPager(pager, Modifier.fillMaxSize().background(SnapColors.surface), key = { group[it].id }) { page ->
        CouponPage(vm, all.firstOrNull { it.id == group[page].id } ?: group[page], onClose, page to group.size)
    }
}

/** "2장 중 1번째" with a dot per coupon: says there is more to swipe to. */
@Composable
private fun SwipeHint(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = "같은 브랜드 기프티콘 ${count}장 중 ${index + 1}번째, 옆으로 넘겨 다른 기프티콘을 볼 수 있어요" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Dot(if (i == index) SnapColors.ink else SnapColors.line, 8.dp)
            Gap(5.dp)
        }
        Gap(3.dp)
        Text("${count}장 중 ${index + 1}번째 · 옆으로 넘겨 보세요", style = SnapType.meta)
    }
}

/** One coupon, sized for the counter: the screenshot (with its barcode) as large as the screen allows. */
@Composable
private fun CouponPage(vm: HomeViewModel, c: Coupon?, onClose: () -> Unit, position: Pair<Int, Int>? = null) {
    val win by vm.window.collectAsState()
    val here by vm.here.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val active = c != null && isActive(c, now)
    val branches: List<Pair<String, Double>> = if (c == null) emptyList() else here?.let { at ->
        win?.branches?.values.orEmpty().filter { it.brandKey == brandKey(c.brand) }
            .map { it.name to distanceMeters(at, Coords(it.latitude, it.longitude)) }.sortedBy { it.second }.take(3)
    }.orEmpty()
    val nearest = branches.firstOrNull()?.takeIf { active && it.second <= BRANCH_RADIUS_M }

    Column(Modifier.fillMaxSize().background(SnapColors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", onClose)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                nearest?.let { (name, d) -> NearChip("지금 근처 · $name ${formatDistance(d)}") }
            }
            if (c != null) Box {
                IconTap(Icons.Filled.MoreVert, "더 보기", { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = SnapColors.surface, shape = SnapShapes.card) {
                    // For demos indoors: the alert this coupon would send near a branch. Not a button on the screen.
                    if (active) DropdownMenuItem(
                        text = { Text("알림 미리 보기", style = SnapType.body) },
                        onClick = { menu = false; vm.testCoupon(c) },
                    )
                    DropdownMenuItem(
                        text = { Text("기프티콘 삭제", style = SnapType.body.copy(color = SnapColors.danger)) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
        if (c == null) {
            BigTitle(null, "기프티콘을 찾지 못했어요", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text("삭제했거나 다른 기기에서 추가한 기프티콘일 수 있어요.", style = SnapType.meta, modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = SnapSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            position?.let { (i, n) -> SwipeHint(i, n, Modifier.padding(horizontal = 4.dp)) }
            BigTitle(c.brand, c.benefit, Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (c.usedAt != null) "사용한 기프티콘이에요" else untilLabel(c.expiresAt) ?: "유효기간 없음",
                    style = SnapType.body.copy(color = SnapColors.ink2),
                    modifier = Modifier.weight(1f),
                )
                if (active) dDay(daysLeft(c, now))?.let {
                    Text(it, style = SnapType.title.copy(color = if (isUrgent(c, now)) SnapColors.urgent else SnapColors.ink))
                }
            }
            Box(Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.bg).padding(12.dp)) {
                LargeShot(c.imageUri)
            }
            if (active && branches.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.bg).padding(horizontal = SnapSpace.card, vertical = 6.dp)) {
                    Text("가까운 지점", style = SnapType.label, modifier = Modifier.padding(vertical = 6.dp))
                    branches.forEach { (name, d) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = SnapType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Gap(8.dp)
                            if (d <= BRANCH_RADIUS_M) NearChip() else Text(formatDistance(d), style = SnapType.label)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SnapSpace.gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap),
        ) {
            if (active) {
                PrimaryButton("사용했어요", { vm.markUsed(c); onClose() }, Modifier.fillMaxWidth())
            } else {
                SecondaryButton("기프티콘 삭제", { confirmDelete = true }, Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmDelete && c != null) {
        ConfirmDelete(
            "${c.brand} 기프티콘 삭제", "이 기프티콘의 지점 알림도 함께 꺼져요.",
            onConfirm = { confirmDelete = false; onClose(); vm.deleteCoupon(c) },
            onDismiss = { confirmDelete = false },
        )
    }
}
