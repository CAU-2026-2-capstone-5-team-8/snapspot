package com.capstone.snapspot.ui.map

import androidx.compose.ui.graphics.Color
import com.capstone.snapspot.ui.theme.SnapColors

enum class PinShape { CIRCLE, SQUARE }

/** A glyph drawn instead of text: a gift box on coupon branches, a map pin on saved places without a screenshot. */
enum class PinIcon { NONE, GIFT, PLACE }

/**
 * How one map label is drawn: a circle or a 4dp-rounded square, a ring, and inside either a short text, an icon,
 * or the place's screenshot (`photo`, a content URI; `grey` desaturates it for ended places).
 */
data class PinStyle(
    val text: String, val fill: Color, val ring: Color, val textColor: Color, val sizeDp: Int, val ringDp: Float, val shape: PinShape,
    val icon: PinIcon = PinIcon.NONE, val photo: String? = null, val grey: Boolean = false,
)

/** An alert radius circle around a saved place. */
data class RadiusStyle(val fill: Color, val stroke: Color)

/**
 * Saved places are circles showing their screenshot (a map pin glyph on ink when there is none); route stops carry
 * their number; coupon stops are white squares.
 */
fun placePin(p: MapPlace): PinStyle = when {
    p.number != null && p.couponStop -> PinStyle("${p.number}", SnapColors.surface, SnapColors.ink, SnapColors.ink, 24, 2f, PinShape.SQUARE)
    p.number != null -> PinStyle("${p.number}", SnapColors.ink, SnapColors.surface, SnapColors.lime, 28, 2f, PinShape.CIRCLE)
    else -> PinStyle(
        "", if (p.ended) SnapColors.muted else SnapColors.ink, if (p.near && !p.ended) SnapColors.lime else SnapColors.surface,
        SnapColors.surface, if (p.selected) 42 else 36, if (p.near && !p.ended) 3f else 2f, PinShape.CIRCLE,
        icon = if (p.photo == null) PinIcon.PLACE else PinIcon.NONE, photo = p.photo, grey = p.ended,
    )
}

/** Coupon branches are small white squares with a gift icon: lime when near, ringed in urgent orange when the coupon ends soon. */
fun branchPin(b: MapBranch): PinStyle = PinStyle(
    "", if (b.near) SnapColors.lime else SnapColors.surface, if (b.urgent) SnapColors.urgent else SnapColors.ink,
    SnapColors.ink, 22, if (b.urgent) 2.5f else 1.5f, PinShape.SQUARE, icon = PinIcon.GIFT,
)

val ME_PIN = PinStyle("", SnapColors.me, SnapColors.surface, SnapColors.surface, 16, 3f, PinShape.CIRCLE)

fun radiusStyle(p: MapPlace): RadiusStyle {
    val tone = when {
        p.ended -> SnapColors.muted
        p.near -> SnapColors.limeDeep
        else -> SnapColors.ink
    }
    val fill = when {
        p.near && !p.ended -> 0.16f
        p.selected -> 0.10f
        else -> 0.05f
    }
    return RadiusStyle(tone.copy(alpha = fill), tone.copy(alpha = if (p.near && !p.ended) 0.9f else 0.35f))
}

/** The status screen's refresh area: a thin ink ring (Kakao polygons have no dashes). */
val REFRESH_AREA = RadiusStyle(SnapColors.ink.copy(alpha = 0.04f), SnapColors.ink.copy(alpha = 0.5f))
