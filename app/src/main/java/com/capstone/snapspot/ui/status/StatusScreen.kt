package com.capstone.snapspot.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.MAX_REGIONS
import com.capstone.snapspot.core.PLACE_GROUP
import com.capstone.snapspot.core.REFRESH_RADIUS_M
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.ui.components.BigTitle
import com.capstone.snapspot.ui.components.Card
import com.capstone.snapspot.ui.components.IconTap
import com.capstone.snapspot.ui.components.Notice
import com.capstone.snapspot.ui.components.SlotMeter
import com.capstone.snapspot.ui.home.HomeViewModel
import com.capstone.snapspot.ui.map.MapBranch
import com.capstone.snapspot.ui.map.MapFocus
import com.capstone.snapspot.ui.map.MapPlace
import com.capstone.snapspot.ui.map.SpotMap
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType

private fun ago(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return when {
        min < 1 -> "방금"
        min < 60 -> "${min}분 전"
        else -> "${min / 60}시간 전"
    }
}

@Composable
fun StatusScreen(vm: HomeViewModel, onBack: () -> Unit, onAskPermissions: () -> Unit, onLocationSettings: () -> Unit) {
    val perms by vm.perms.collectAsState()
    val win by vm.window.collectAsState()
    val sync by vm.sync.collectAsState()
    val watching by vm.watching.collectAsState()
    val places by vm.places.collectAsState()
    val coupons by vm.coupons.collectAsState()
    val here by vm.here.collectAsState()
    val brandNames = coupons.associate { brandKey(it.coupon.brand) to it.coupon.brand }
    val now = System.currentTimeMillis()

    // Places in ink, coupon branches in lime-deep (urgent brands in orange), the refresh region in grey.
    val parts = buildList {
        add(Triple("저장한 장소", win?.byGroup?.get(PLACE_GROUP) ?: 0, SnapColors.ink))
        win?.byGroup.orEmpty().filterKeys { it != PLACE_GROUP }.forEach { (g, n) ->
            val urgent = win?.branches?.values?.any { it.brandKey == g && it.urgent } == true
            add(Triple("${brandNames[g] ?: g} 지점${if (urgent) " · 임박" else ""}", n, if (urgent) SnapColors.urgent else SnapColors.limeDeep))
        }
        if (win?.here != null) add(Triple("갱신 구역 (반경 ${(REFRESH_RADIUS_M / 1000).toInt()}km)", 1, SnapColors.muted.copy(alpha = 0.45f)))
    }
    val used = parts.sumOf { it.second }

    Column(Modifier.fillMaxSize().background(SnapColors.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { IconTap(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", onBack) }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = SnapSpace.gutter).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SnapSpace.gap),
        ) {
            BigTitle(
                if (perms.alertsOn) null else "지금은",
                if (perms.alertsOn) "알림 장소 ${watching}곳" else "앱을 열어 둔 동안만 알려 드려요",
                Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
            )

            if (!(perms.notif && perms.fg && perms.bg)) Notice("앱을 닫아도 알림을 받으려면 알림 권한과 위치 \"항상 허용\"이 필요해요.", "권한 설정하기" to onAskPermissions)
            if (perms.bg && !perms.net) Notice("휴대폰의 \"Google 위치 정확도\"가 꺼져 있어서 앱을 닫으면 알림이 오지 않아요.", "위치 정확도 켜기" to onLocationSettings)
            sync?.error?.takeIf { perms.bg }?.let { Notice("지오펜스를 등록하지 못했어요: $it") }

            Card {
                Column(Modifier.padding(SnapSpace.card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${MAX_REGIONS}칸 중 ${used}칸 사용", style = SnapType.title.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        Text("${maxOf(MAX_REGIONS - used, 0)}칸 남음", style = SnapType.meta)
                    }
                    SlotMeter(MAX_REGIONS, parts)
                    Text(
                        "휴대폰은 한 번에 ${MAX_REGIONS}곳까지만 알림을 걸 수 있어요. 저장한 장소를 먼저, 그다음 유효기간이 임박한 쿠폰 순으로 담아요. " +
                            "지금 위치에서 ${(REFRESH_RADIUS_M / 1000).toInt()}km를 벗어나면 그 자리에서 다시 골라요.",
                        style = SnapType.meta,
                    )
                }
            }

            win?.here?.let { center ->
                SpotMap(
                    places = places.map { MapPlace(it.id, Coords(it.latitude, it.longitude), it.radius.toDouble(), it.name.take(1), placeEnded(it, now), false) },
                    branches = win?.branches.orEmpty().map { (id, b) -> MapBranch(id, Coords(b.latitude, b.longitude), b.urgent, false) },
                    here = here,
                    focus = MapFocus(center, 0, 14),
                    refresh = center to REFRESH_RADIUS_M,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    cornerRadius = 10.dp,
                    maskColor = SnapColors.bg,
                )
            }

            Card {
                Column(Modifier.padding(SnapSpace.card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("마지막으로 다시 고른 때", style = SnapType.label)
                    val w = win
                    if (w == null) {
                        Text("아직 고른 적이 없어요. 위치 권한을 허용하면 바로 시작해요.", style = SnapType.meta)
                    } else {
                        FactRow("시각", ago(w.at))
                        FactRow("카카오 지점 검색", if (w.requests == 0) "저장해 둔 결과 사용" else "${w.requests}회")
                        FactRow("기준 위치", if (w.here != null) "확인됨" else "아직 모름")
                    }
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = SnapType.body.copy(color = SnapColors.ink2), modifier = Modifier.weight(1f))
        Text(value, style = SnapType.body.copy(fontWeight = FontWeight.Bold))
    }
}
