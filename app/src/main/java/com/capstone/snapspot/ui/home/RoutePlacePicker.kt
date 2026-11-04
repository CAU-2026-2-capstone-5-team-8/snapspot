package com.capstone.snapspot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.capstone.snapspot.core.MIN_ROUTE_PLACES
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.ui.components.IconTap
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.Shot
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType

/** Draft checkbox changes stay here until applied; dismissing leaves the current course alone. */
@Composable
internal fun RoutePlacePicker(
    requestId: Long,
    places: List<Place>,
    originalIds: Set<String>,
    initialIds: Set<String>,
    now: Long,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Boolean,
) {
    val available = remember(places, originalIds, now / 60_000L) {
        places.filterNot { placeEnded(it, now) }.distinctBy { it.id }
            .sortedWith(compareBy<Place> { it.id !in originalIds }.thenBy { it.name }.thenBy { it.id })
    }
    var chosen by rememberSaveable(requestId) { mutableStateOf(initialIds.toList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val eligible = chosen.toSet().intersect(available.map { it.id }.toSet())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
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
            Modifier.fillMaxSize().background(SnapColors.bg).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = SnapSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTap(Icons.AutoMirrored.Filled.ArrowBack, "갈 곳 선택 닫기", onDismiss)
                Text("갈 곳 고르기", style = SnapType.title, modifier = Modifier.weight(1f))
                TextButton(onClick = { chosen = originalIds.toList(); error = null }) {
                    Text("자동 선택", style = SnapType.label.copy(color = SnapColors.ink))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${eligible.size}곳 선택했어요", style = SnapType.display)
                Text("저장한 다른 장소도 넣을 수 있어요. ${MIN_ROUTE_PLACES}곳 이상 골라 주세요.", style = SnapType.meta)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(SnapSpace.gap),
            ) {
                items(available, key = { it.id }) { place ->
                    val checked = place.id in eligible
                    Row(
                        Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface)
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { on ->
                                chosen = if (on) (chosen + place.id).distinct() else chosen - place.id
                                error = null
                            }).padding(SnapSpace.card),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = SnapColors.ink, checkmarkColor = SnapColors.lime),
                        )
                        Shot(place.imageUri, 40.dp, 40.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(place.name, style = SnapType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(place.address.ifBlank { place.category }, style = SnapType.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (place.id in originalIds) Text("자동 묶음에 있던 곳", style = SnapType.meta.copy(color = SnapColors.ink2))
                        }
                    }
                }
            }
            error?.let { Text(it, style = SnapType.meta.copy(color = SnapColors.danger)) }
            Text("이번 코스에만 적용해요. 저장한 장소와 근처 알림은 유지돼요.", style = SnapType.meta)
            PrimaryButton(
                "선택한 ${eligible.size}곳 동선 보기",
                onClick = { if (!onApply(eligible)) error = "장소 목록이 바뀌었어요. ${MIN_ROUTE_PLACES}곳 이상 다시 골라 주세요." },
                enabled = eligible.size >= MIN_ROUTE_PLACES,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}
