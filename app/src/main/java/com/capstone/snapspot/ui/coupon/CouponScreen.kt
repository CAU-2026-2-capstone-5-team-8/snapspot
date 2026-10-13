package com.capstone.snapspot.ui.coupon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.capstone.snapspot.core.untilLabel
import com.capstone.snapspot.ui.components.LargeShot
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.QuietButton
import com.capstone.snapspot.ui.components.SecondaryButton
import com.capstone.snapspot.ui.home.HomeViewModel
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapFonts
import com.capstone.snapspot.ui.theme.SnapType

/** One coupon, sized for the counter: the screenshot (with its barcode) as large as the screen allows. */
@Composable
fun CouponScreen(vm: HomeViewModel, id: String, onClose: () -> Unit) {
    val items by vm.coupons.collectAsState()
    val win by vm.window.collectAsState()
    val here by vm.here.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val c = items.firstOrNull { it.coupon.id == id }?.coupon

    Column(Modifier.fillMaxSize().background(SnapColors.paper).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(c?.brand ?: "쿠폰을 찾지 못했어요", fontFamily = SnapFonts.display, fontSize = SnapType.hero.fontSize * 0.7f, color = SnapColors.ink)
                c?.let { Text(it.benefit, style = SnapType.body.copy(fontWeight = FontWeight.Medium)) }
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(SnapColors.surface).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "닫기", tint = SnapColors.ink)
            }
        }
        if (c == null) {
            Text("삭제했거나 다른 기기에서 추가한 쿠폰일 수 있어요.", style = SnapType.meta, modifier = Modifier.padding(20.dp))
            return@Column
        }
        val now = System.currentTimeMillis()
        val active = isActive(c, now)
        val key = brandKey(c.brand)
        val branches = here?.let { at ->
            win?.branches?.values.orEmpty().filter { it.brandKey == key }
                .map { it.name to distanceMeters(at, Coords(it.latitude, it.longitude)) }.sortedBy { it.second }.take(3)
        }.orEmpty()

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SnapColors.surface).border(1.dp, SnapColors.line, RoundedCornerShape(16.dp)).padding(12.dp)) {
                LargeShot(c.imageUri)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (c.usedAt != null) "사용한 쿠폰이에요" else untilLabel(c.expiresAt) ?: "유효기간 없음", style = SnapType.body, modifier = Modifier.weight(1f))
                if (active) dDay(daysLeft(c, now))?.let {
                    Text(it, fontFamily = SnapFonts.display, fontSize = SnapType.distance.fontSize * 1.2f, color = if (isUrgent(c, now)) SnapColors.urgent else SnapColors.green)
                }
            }
            if (active && branches.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.surface).border(1.dp, SnapColors.line, RoundedCornerShape(12.dp))) {
                    Text("가까운 지점", style = SnapType.label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    branches.forEach { (name, d) ->
                        HorizontalDivider(color = SnapColors.line)
                        Row(Modifier.background(if (d <= BRANCH_RADIUS_M) SnapColors.highlight else SnapColors.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(name, style = SnapType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatDistance(d), style = SnapType.distance)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = SnapColors.line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                QuietButton("삭제", { confirmDelete = true })
                SecondaryButton("알림 테스트", { vm.testCoupon(c) }, Modifier.weight(1f))
                PrimaryButton("사용했어요", { vm.markUsed(c); onClose() }, Modifier.weight(1.4f))
            } else {
                SecondaryButton("쿠폰 삭제", { confirmDelete = true }, Modifier.weight(1f))
            }
        }
    }

    if (confirmDelete && c != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${c.brand} 쿠폰 삭제", style = SnapType.title) },
            text = { Text("이 쿠폰의 지점 알림도 함께 꺼져요.", style = SnapType.body) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onClose(); vm.deleteCoupon(c) }) { Text("삭제", color = SnapColors.amber, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소", color = SnapColors.ink) } },
            containerColor = SnapColors.surface,
        )
    }
}
