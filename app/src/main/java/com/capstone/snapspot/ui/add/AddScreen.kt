package com.capstone.snapspot.ui.add

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth as fmw
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.core.CONFIDENT
import com.capstone.snapspot.core.Candidate
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.ui.components.Field
import com.capstone.snapspot.ui.components.Notice
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.SecondaryButton
import com.capstone.snapspot.ui.components.Segmented
import com.capstone.snapspot.ui.components.Shot
import com.capstone.snapspot.ui.map.MapFocus
import com.capstone.snapspot.ui.map.MapPlace
import com.capstone.snapspot.ui.map.SpotMap
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapType
import kotlinx.coroutines.launch

@Composable
fun AddScreen(vm: AddViewModel, onClose: () -> Unit, onSaved: (kind: String, id: String) -> Unit, onOpenPlace: (String) -> Unit, onOpenCoupon: (String) -> Unit) {
    val draft by vm.draft.collectAsState()
    val error by vm.error.collectAsState()
    val scope = rememberCoroutineScope()
    var duplicate by remember { mutableStateOf<SaveResult?>(null) }
    val d = draft ?: return
    val ready = d.stage == Stage.READY
    val isCoupon = d.kind == "coupon"

    fun save(allowDuplicate: Boolean = false) = scope.launch {
        when (val r = vm.save(allowDuplicate)) {
            is SaveResult.Saved -> onSaved(r.kind, r.id)
            is SaveResult.DuplicatePlace, is SaveResult.DuplicateCoupon -> duplicate = r
            SaveResult.NotReady -> Unit
        }
    }

    Column(Modifier.fillMaxSize().background(SnapColors.paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (!ready) "스크린샷 읽는 중" else if (isCoupon) "새 쿠폰 확인" else "새 장소 확인", style = SnapType.title, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(SnapColors.surface).clickable { vm.cancel(); onClose() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "닫기", tint = SnapColors.ink)
            }
        }
        if (!ready) {
            Reading(d)
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Segmented(listOf("place", "coupon"), d.kind, vm::setKind, { if (it == "place") "가 볼 장소" else "프랜차이즈 쿠폰" })
                if (isCoupon) CouponForm(vm, d) else PlaceForm(vm, d)
            }
            Column(Modifier.fillMaxWidth().background(SnapColors.paper)) {
                HorizontalDivider(color = SnapColors.line)
                PrimaryButton(
                    if (isCoupon) "쿠폰함에 저장" else "지도에 저장",
                    onClick = { save() },
                    enabled = if (isCoupon) d.brand.isNotBlank() else d.coords != null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }

    error?.let { msg ->
        val (title, body) = msg.split("\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text(title, style = SnapType.title) },
            text = { Text(body, style = SnapType.body) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("확인", color = SnapColors.green) } },
            containerColor = SnapColors.surface,
        )
    }
    when (val dup = duplicate) {
        is SaveResult.DuplicatePlace -> AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("이미 저장한 장소예요", style = SnapType.title) },
            text = { Text("${dup.existing.name}은(는) 이미 지도에 있어요. 같은 곳에 알림이 두 번 울리지 않게 새로 저장하지 않을게요.", style = SnapType.body) },
            confirmButton = { TextButton(onClick = { duplicate = null; vm.cancel(); onOpenPlace(dup.existing.id) }) { Text("기존 것 보기", color = SnapColors.green, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("취소", color = SnapColors.ink) } },
            containerColor = SnapColors.surface,
        )
        is SaveResult.DuplicateCoupon -> AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("같은 쿠폰이 이미 있어요", style = SnapType.title) },
            text = { Text("${dup.existing.brand} ${dup.existing.benefit} 쿠폰이 쿠폰함에 있어요. 같은 쿠폰을 한 장 더 가지고 있다면 그래도 추가할 수 있어요.", style = SnapType.body) },
            confirmButton = {
                Row {
                    TextButton(onClick = { duplicate = null; save(allowDuplicate = true) }) { Text("그래도 추가", color = SnapColors.ink) }
                    TextButton(onClick = { duplicate = null; vm.cancel(); onOpenCoupon(dup.existing.id) }) { Text("기존 것 보기", color = SnapColors.green, fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("취소", color = SnapColors.muted) } },
            containerColor = SnapColors.surface,
        )
        else -> Unit
    }
}

@Composable
private fun PlaceForm(vm: AddViewModel, d: Draft) {
    val here by vm.here.collectAsState()
    val busy by vm.busy.collectAsState()
    if (!d.isPlace) Notice("이 이미지에서는 장소를 찾기 어려웠어요. 이름과 주소를 직접 고쳐 주세요.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Shot(d.imageUri, 96.dp, 128.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("장소 이름", d.name, { v -> vm.update { it.copy(name = v) } }, large = true)
            Field("분류", d.category, { v -> vm.update { it.copy(category = v) } })
        }
    }
    Field("메모", d.note, { v -> vm.update { it.copy(note = v) } })
    Field("주소나 위치 단서", d.address, { v -> vm.update { it.copy(address = v) } })
    Field("종료일 (팝업·전시만)", d.endDate, { v -> vm.update { it.copy(endDate = v) } }, placeholder = "예: 2026-10-31 (없으면 비워 두세요)")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("지도에서 찾은 위치", style = SnapType.label)
        MatchBox(d.matched, d.coords != null)
        if (d.coords != null && here != null) Text("여기서 ${formatDistance(distanceMeters(here!!, d.coords))}", style = SnapType.meta)
        val others = d.candidates.filter { it != d.matched }.take(3)
        if (others.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.surface).border(1.dp, SnapColors.line, RoundedCornerShape(12.dp))) {
                Text("다른 후보", style = SnapType.label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                others.forEach { c -> CandidateRow(c) { vm.pickCandidate(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("주소로 다시 찾기", vm::regeocode, Modifier.weight(1f), busy = busy)
            SecondaryButton("지금 위치로", vm::pinToHere, Modifier.weight(1f))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("이만큼 가까워지면 알려 드려요", style = SnapType.label)
        Segmented(RADII, d.radius, { r -> vm.update { it.copy(radius = r) } }, { "${it}m" })
        d.coords?.let { at ->
            // The chosen radius, drawn right away on a small map.
            SpotMap(
                places = listOf(MapPlace("draft", at, d.radius.toDouble(), d.name.firstOrNull()?.toString() ?: "?", ended = false, selected = true)),
                branches = emptyList(),
                here = here,
                focus = MapFocus(at, d.radius.toLong(), zoomFor(d.radius)),
                modifier = Modifier.fillMaxWidth().height(180.dp),
                cornerRadius = 16.dp,
                interactive = false,
            )
        }
    }
}

private fun zoomFor(radius: Int) = when {
    radius <= 100 -> 17
    radius <= 300 -> 16
    else -> 15
}

@Composable
private fun MatchBox(matched: Candidate?, hasCoords: Boolean) {
    val (bg, head, body) = when {
        !hasCoords -> Triple(SnapColors.amberSoft, "위치를 못 찾았어요", "주소를 고치고 다시 찾거나 \"지금 위치로\"를 눌러 주세요.")
        matched == null -> Triple(SnapColors.amberSoft, "주소로 짐작한 위치예요", "카카오맵에서 같은 가게를 찾지 못했어요. 핀이 맞는지 확인해 주세요.")
        matched.score >= CONFIDENT -> Triple(SnapColors.greenSoft, matched.name, "카카오맵에서 찾았어요 · ${matched.address}")
        else -> Triple(SnapColors.amberSoft, matched.name, "비슷한 곳이에요. 맞는지 확인해 주세요 · ${matched.address}")
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(head, style = SnapType.title.copy(fontSize = SnapType.body.fontSize, color = if (bg == SnapColors.amberSoft) SnapColors.amber else SnapColors.ink))
        Text(body, style = SnapType.meta.copy(color = if (bg == SnapColors.amberSoft) SnapColors.amber else SnapColors.muted))
    }
}

@Composable
private fun CandidateRow(c: Candidate, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        HorizontalDivider(color = SnapColors.line)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = SnapType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.address, style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("선택", style = SnapType.label.copy(color = SnapColors.green))
        }
    }
}

/** Coupon fields plus a preview of the branches that will trigger alerts. */
@Composable
private fun CouponForm(vm: AddViewModel, d: Draft) {
    val here by vm.here.collectAsState()
    val busy by vm.busy.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Shot(d.imageUri, 96.dp, 128.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("브랜드", d.brand, { v -> vm.update { it.copy(brand = v) } }, large = true)
            Field("받을 수 있는 것", d.benefit, { v -> vm.update { it.copy(benefit = v) } })
        }
    }
    Field("유효기간 마지막 날", d.endDate, { v -> vm.update { it.copy(endDate = v) } }, placeholder = "예: 2026-12-31 (없으면 비워 두세요)")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("알림이 울릴 지점", style = SnapType.label)
        val branches = d.branches
        val at = here
        when {
            at == null -> Notice("위치를 아직 몰라요. 위치를 허용하면 근처 지점을 미리 보여 드려요. 저장은 지금 해도 돼요.")
            branches == null -> Preview("근처 지점을 찾는 중이에요", null)
            branches.isEmpty() -> Notice("근처에서 ${d.brand.ifBlank { "이 브랜드" }} 지점을 못 찾았어요. 브랜드 이름을 고친 뒤 다시 찾아 보세요. 멀리 가면 그곳 지점을 다시 찾아요.")
            else -> {
                val shown = branches.take(7)
                Preview(
                    "가까운 ${d.brand} ${shown.size}곳을 지켜봐요",
                    "가장 가까운 곳은 ${shown[0].name}(${formatDistance(distanceMeters(at, Coords(shown[0].latitude, shown[0].longitude)))})이에요. 이동하면 그 근처 지점으로 바꿔 가며 알려 드려요.",
                )
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.surface).border(1.dp, SnapColors.line, RoundedCornerShape(12.dp))) {
                    shown.take(3).forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider(color = SnapColors.line)
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(b.name, style = SnapType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatDistance(distanceMeters(at, Coords(b.latitude, b.longitude))), style = SnapType.meta)
                        }
                    }
                }
            }
        }
        SecondaryButton("브랜드로 다시 찾기", vm::refreshBranchPreview, Modifier.fillMaxWidth(), busy = busy)
    }
}

@Composable
private fun Preview(head: String, body: String?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.greenSoft).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(head, style = SnapType.title.copy(fontSize = SnapType.body.fontSize))
        body?.let { Text(it, style = SnapType.meta) }
    }
}

/** The screenshot, with a highlighter sweeping over it while it is being read. */
@Composable
private fun Reading(d: Draft) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "y",
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        BoxWithConstraints(Modifier.fmw(0.62f).aspectRatio(9f / 16f).clip(RoundedCornerShape(18.dp)).border(4.dp, SnapColors.surface, RoundedCornerShape(18.dp))) {
            Shot(d.imageUri, maxWidth, maxHeight)
            Box(Modifier.fillMaxWidth().height(40.dp).offset(y = (maxHeight + 40.dp) * sweep - 40.dp).background(SnapColors.highlight.copy(alpha = 0.55f)))
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step("스크린샷에서 장소 정보 읽기", if (d.stage == Stage.READING) 1 else 2)
            Step(if (d.kind == "coupon") "근처 지점 찾기" else "카카오맵에서 장소 찾기", if (d.stage == Stage.LOCATING) 1 else 0)
            if (d.name.isNotBlank()) Text("찾은 이름: ${d.name}", style = SnapType.meta)
        }
    }
}

/** state: 0 todo, 1 now, 2 done. */
@Composable
private fun Step(label: String, state: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (state == 2) SnapColors.green else SnapColors.paper)
                .border(2.dp, if (state == 0) SnapColors.line else SnapColors.green, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (state == 2) Icon(Icons.Filled.Check, null, tint = SnapColors.surface, modifier = Modifier.size(12.dp)) }
        Text(label, style = SnapType.body.copy(color = if (state == 0) SnapColors.muted else SnapColors.ink, fontWeight = if (state == 1) FontWeight.Bold else FontWeight.Normal))
    }
}
