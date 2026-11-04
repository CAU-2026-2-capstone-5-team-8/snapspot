package com.capstone.snapspot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.MIN_ROUTE_PLACES
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.moveRoutePlace
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.core.recommendRouteOrder
import com.capstone.snapspot.ui.components.IconTap
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.Shot
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Selection and drag order are drafts until confirmed; dismissing preserves the previous course. */
@Composable
internal fun RoutePlacePicker(
    requestId: Long, places: List<Place>, originalIds: Set<String>, initialIds: Set<String>,
    initialOrderedPlaceIds: List<String>, here: Coords?, now: Long,
    onDismiss: () -> Unit, onApply: (List<String>) -> Boolean,
) {
    val available = remember(places, originalIds, now / 60_000L) {
        places.filterNot { placeEnded(it, now) }.distinctBy { it.id }
            .sortedWith(compareBy<Place> { it.id !in originalIds }.thenBy { it.name }.thenBy { it.id })
    }
    val byId = available.associateBy { it.id }
    var chosen by rememberSaveable(requestId) { mutableStateOf((initialOrderedPlaceIds + initialIds.sorted()).distinct()) }
    var userOrdered by rememberSaveable(requestId) { mutableStateOf(initialOrderedPlaceIds.isNotEmpty()) }
    var sortRequest by rememberSaveable(requestId) { mutableIntStateOf(0) }
    var appliedSortRequest by remember(requestId) { mutableIntStateOf(0) }
    var sorting by remember(requestId) { mutableStateOf(false) }
    var error by remember(requestId) { mutableStateOf<String?>(null) }
    val orderedIds = chosen.distinct().filter { it in byId }
    val selected = orderedIds.map { byId.getValue(it) }
    val excluded = available.filter { it.id !in orderedIds }
    val list = rememberLazyListState()

    // A late first GPS fix may improve the default. A tap or drag freezes the user's draft order.
    LaunchedEffect(requestId, selected.map { it.id }.toSet(), here, userOrdered, sortRequest) {
        if (userOrdered) { sorting = false; return@LaunchedEffect }
        sorting = true
        try {
            val recommended = withContext(Dispatchers.Default) { recommendRouteOrder(selected, here) }
            // A fresh location may update the suggestion while the user browses other saved places.
            // Preserve that viewport; explicitly asking to sort/reset still reveals the first place.
            if (sortRequest != appliedSortRequest || !list.canScrollBackward && !list.isScrollInProgress) {
                list.requestScrollToItem(0)
            }
            chosen = recommended
            appliedSortRequest = sortRequest
        }
        finally { if (isActive) sorting = false }
    }
    val move: (String, String) -> Unit = { from, to ->
        userOrdered = true
        // Keep the viewport at the same slot instead of anchoring it to a row whose index moved.
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        chosen = moveRoutePlace(orderedIds, from, to)
        error = null
    }
    val drag = rememberRouteOrderDragState(list, orderedIds, move)
    val toggle: (String) -> Unit = { id ->
        userOrdered = true
        chosen = if (id in orderedIds) orderedIds - id else orderedIds + id
        error = null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
        Column(
            Modifier.fillMaxSize().background(SnapColors.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = SnapSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTap(Icons.AutoMirrored.Filled.ArrowBack, "갈 곳 선택 닫기", onDismiss)
                Text("갈 곳 고르기", style = SnapType.title, modifier = Modifier.weight(1f))
                TextButton(onClick = { chosen = originalIds.sorted(); userOrdered = false; sortRequest++; error = null }) {
                    Text("초기화", style = SnapType.label.copy(color = SnapColors.ink))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size}곳 선택했어요", style = SnapType.display, modifier = Modifier.weight(1f))
                    TextButton(onClick = { userOrdered = false; sortRequest++ }, enabled = selected.isNotEmpty()) {
                        Text("자동 정렬", style = SnapType.label.copy(color = SnapColors.ink))
                    }
                }
                Text("원을 누르면 추가·제외, 손잡이를 끌면 방문 순서를 바꿀 수 있어요.", style = SnapType.meta)
                Text(
                    if (selected.size < MIN_ROUTE_PLACES) "${MIN_ROUTE_PLACES}곳 이상 골라 주세요."
                    else if (sorting) "방문 순서를 추천하고 있어요."
                    else if (here == null) "위치를 확인하지 못해도 순서를 직접 정할 수 있어요."
                    else "자동 정렬은 현재 위치에서 가까운 곳을 1번으로 추천해요.",
                    style = SnapType.meta,
                )
            }
            LazyColumn(
                state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(SnapSpace.gap),
            ) {
                itemsIndexed(selected, key = { _, place -> place.id }) { i, place ->
                    val grabbed = drag.draggingId == place.id
                    RouteChoiceRow(
                        place, i + 1, { toggle(place.id) },
                        modifier = (if (grabbed) Modifier else Modifier.animateItem()).zIndex(if (grabbed) 1f else 0f)
                            .graphicsLayer { translationY = drag.translation(place.id) }
                            .shadow(if (grabbed) 8.dp else 0.dp, SnapShapes.card),
                        handle = {
                            Box(
                                Modifier.size(48.dp).semantics {
                                    contentDescription = "${place.name} 순서 변경"
                                    customActions = buildList {
                                        if (i > 0) add(CustomAccessibilityAction("위로 이동") { move(place.id, orderedIds[i - 1]); true })
                                        if (i < orderedIds.lastIndex) add(CustomAccessibilityAction("아래로 이동") { move(place.id, orderedIds[i + 1]); true })
                                    }
                                }.pointerInput(place.id) {
                                    detectDragGestures(
                                        onDragStart = { userOrdered = true; drag.start(place.id) },
                                        onDragCancel = drag::finish, onDragEnd = drag::finish,
                                        onDrag = { change, amount -> change.consume(); drag.drag(amount.y) },
                                    )
                                }, contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.DragIndicator, contentDescription = null, tint = SnapColors.ink2, modifier = Modifier.size(24.dp)) }
                        },
                    )
                }
                if (excluded.isNotEmpty()) item(key = "other-places-header") {
                    Text("저장한 다른 장소", style = SnapType.label, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
                items(excluded, key = { it.id }) { place ->
                    RouteChoiceRow(place, null, { toggle(place.id) }, Modifier.animateItem())
                }
            }
            error?.let { Text(it, style = SnapType.meta.copy(color = SnapColors.danger)) }
            Text("기프티콘 지점은 정한 장소 순서를 지키면서 함께 넣어요. 저장한 장소와 알림은 유지돼요.", style = SnapType.meta)
            PrimaryButton(
                "선택한 ${selected.size}곳 동선 보기",
                onClick = { if (!onApply(orderedIds)) error = "장소 목록이 바뀌었어요. ${MIN_ROUTE_PLACES}곳 이상 다시 골라 주세요." },
                enabled = selected.size >= MIN_ROUTE_PLACES && !sorting,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun RouteChoiceRow(place: Place, number: Int?, onToggle: () -> Unit, modifier: Modifier = Modifier, handle: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).toggleable(value = number != null, role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics { stateDescription = number?.let { "${it}번째 방문" } ?: "코스에서 제외" }
                .padding(SnapSpace.card),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(if (number != null) SnapColors.ink else SnapColors.surface)
                    .then(if (number == null) Modifier.border(1.5.dp, SnapColors.ink2, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { if (number != null) Text("$number", style = SnapType.button.copy(color = SnapColors.lime, fontSize = 17.sp)) }
            Shot(place.imageUri, 40.dp, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(place.name, style = SnapType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(place.address.ifBlank { place.category }, style = SnapType.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        handle?.invoke()
    }
}
