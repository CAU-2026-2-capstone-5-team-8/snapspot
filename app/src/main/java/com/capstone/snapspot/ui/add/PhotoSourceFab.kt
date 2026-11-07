package com.capstone.snapspot.ui.add

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapType

/** A small expanding menu anchored to the existing add button, above the home sheet. */
@Composable
fun PhotoSourceFab(onCamera: () -> Unit, onGallery: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        if (expanded) 45f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing), label = "addRotation",
    )
    BackHandler(expanded) { expanded = false }

    Box(Modifier.fillMaxSize()) {
        // Intercept the outside tap so dismissing never activates a map pin or list row beneath it.
        if (expanded) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "사진 추가 메뉴 닫기", onClick = { expanded = false },
            ).semantics { contentDescription = "사진 추가 메뉴 닫기" })
        }
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 2 } +
                    scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.8f, transformOrigin = TransformOrigin(1f, 1f)),
                exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 2 } +
                    scaleOut(tween(180), targetScale = 0.8f, transformOrigin = TransformOrigin(1f, 1f)),
            ) {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceAction("카메라", Icons.Outlined.CameraAlt, expanded) { expanded = false; onCamera() }
                    SourceAction("갤러리", Icons.Outlined.PhotoLibrary, expanded) { expanded = false; onGallery() }
                }
            }
            SourceButton(
                Icons.Filled.Add, if (expanded) "사진 추가 메뉴 닫기" else "사진 추가",
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" },
                iconModifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
private fun SourceAction(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, style = SnapType.label.copy(color = SnapColors.ink),
            modifier = Modifier.shadow(2.dp, SnapShapes.small).clip(SnapShapes.small)
                .background(SnapColors.surface).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        SourceButton(icon, label, onClick, enabled = enabled)
    }
}

@Composable
private fun SourceButton(
    icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, iconModifier: Modifier = Modifier, enabled: Boolean = true,
) {
    Box(
        modifier.size(56.dp).shadow(6.dp, SnapShapes.card).clip(SnapShapes.card)
            .background(SnapColors.ink).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.semantics { contentDescription = label } else Modifier.clearAndSetSemantics {}),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = SnapColors.lime, modifier = iconModifier.size(28.dp))
    }
}
