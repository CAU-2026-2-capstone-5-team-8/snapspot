package com.capstone.snapspot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.R

/**
 * Ink + lime: ink carries text, actions and routes; lime means one thing only, "near you now"
 * (and the numbers of the route you are about to walk).
 */
object SnapColors {
    val ink = Color(0xFF191F28)
    val ink2 = Color(0xFF4E5968)
    /** Meta text: 4.6:1 on white. */
    val muted = Color(0xFF6B7684)
    val line = Color(0xFFE5E8EB)
    val bg = Color(0xFFF2F4F6)
    val surface = Color(0xFFFFFFFF)
    val lime = Color(0xFFD9F99D)
    val limeDeep = Color(0xFF65A30D)
    val limeInk = Color(0xFF1A2E05)
    /** The user's dot on the map, nowhere else. */
    val me = Color(0xFF3B82F6)
    val danger = Color(0xFFC9252D)
    /** Coupons that end within 3 days. */
    val urgent = Color(0xFFC2410C)

}

object SnapFonts {
    val sans = FontFamily(
        Font(R.font.pretendard_regular, FontWeight.Normal),
        Font(R.font.pretendard_semibold, FontWeight.SemiBold),
        Font(R.font.pretendard_bold, FontWeight.Bold),
        Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
    )

}

private val tight = (-0.3).sp

object SnapType {
    /** The big sentence on top of a screen or sheet. */
    val display = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = tight, color = SnapColors.ink)
    val logo = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = tight, color = SnapColors.ink)
    val title = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = tight, color = SnapColors.ink)
    val body = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = tight, color = SnapColors.ink)
    val label = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = tight, color = SnapColors.ink2)
    val meta = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = tight, color = SnapColors.muted)
    /** Button text: no color, the button decides. */
    val button = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = tight)

}

object SnapShapes {
    val card = RoundedCornerShape(10.dp)
    val small = RoundedCornerShape(8.dp)
    val sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    /** Corner of the square map pins (coupon branches, coupon stops), in dp. */
    const val PIN_CORNER_DP = 4f
}

object SnapSpace {
    /** Screen side padding. */
    val gutter = 16.dp
    /** Padding inside a card. */
    val card = 14.dp
    /** Between cards. */
    val gap = 8.dp
}

@Composable
fun SnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = SnapColors.ink,
            onPrimary = SnapColors.surface,
            background = SnapColors.bg,
            onBackground = SnapColors.ink,
            surface = SnapColors.surface,
            onSurface = SnapColors.ink,
            onSurfaceVariant = SnapColors.ink2,
            outline = SnapColors.line,
            error = SnapColors.danger,
        ),
        typography = Typography(
            bodyLarge = SnapType.body.copy(color = Color.Unspecified),
            bodyMedium = SnapType.body.copy(color = Color.Unspecified),
            titleLarge = SnapType.title.copy(color = Color.Unspecified),
            labelLarge = SnapType.button,
        ),
        content = content,
    )
}
