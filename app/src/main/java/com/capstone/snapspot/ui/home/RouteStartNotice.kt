package com.capstone.snapspot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType

/** A fixed explanation of the route's start, separate from numbered, reorderable visits. */
@Composable
internal fun RouteStartNotice(includesCurrentLocation: Boolean, locationKnown: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface)
            .semantics(mergeDescendants = true) {}.padding(SnapSpace.card),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(SnapColors.bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (includesCurrentLocation) Icons.Filled.MyLocation else Icons.Filled.Flag,
                contentDescription = null,
                tint = if (includesCurrentLocation) SnapColors.me else SnapColors.ink2,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (includesCurrentLocation) "출발 · 현재 위치" else "첫 장소부터의 코스예요",
                style = SnapType.label.copy(color = SnapColors.ink),
            )
            Text(
                when {
                    includesCurrentLocation -> "현재 위치에서 1번 장소까지의 이동도 포함해요."
                    locationKnown -> "현재 위치가 코스에서 멀어, 첫 장소까지의 이동은 포함하지 않아요."
                    else -> "현재 위치를 확인하지 못해, 첫 장소까지의 이동은 포함하지 않아요."
                },
                style = SnapType.meta,
            )
        }
    }
}
