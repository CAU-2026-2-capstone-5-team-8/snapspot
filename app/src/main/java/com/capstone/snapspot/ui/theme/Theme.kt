package com.capstone.snapspot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.R

/**
 * "Highlighter on a map": map paper and ink, park green for actions,
 * and highlighter yellow reserved for one meaning only — "you are near this place".
 */
object SnapColors {
    val paper = Color(0xFFF2F3EE)
    val surface = Color(0xFFFFFFFF)
    val ink = Color(0xFF1F2B2A)
    val muted = Color(0xFF6C7774)
    val line = Color(0xFFD9DED6)
    val green = Color(0xFF2D7A57)
    val greenSoft = Color(0xFFE3EFE7)
    val highlight = Color(0xFFFFE34D)
    val amber = Color(0xFF8A5A0A)
    val amberSoft = Color(0xFFFBF0D4)
    /** Ring for branches of a coupon that expires within 3 days. */
    val urgent = Color(0xFFE07B00)
}

object SnapFonts {
    val display = FontFamily(Font(R.font.black_han_sans))
    val body = FontFamily(
        Font(R.font.plex_kr_regular, FontWeight.Normal),
        Font(R.font.plex_kr_medium, FontWeight.Medium),
        Font(R.font.plex_kr_bold, FontWeight.Bold),
    )
}

/** Type scale on a 4px grid, as in the original app. */
object SnapType {
    val hero = TextStyle(fontFamily = SnapFonts.display, fontSize = 40.sp, lineHeight = 46.sp, color = SnapColors.ink)
    val logo = TextStyle(fontFamily = SnapFonts.display, fontSize = 26.sp, lineHeight = 30.sp, color = SnapColors.ink)
    val title = TextStyle(fontFamily = SnapFonts.body, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp, color = SnapColors.ink)
    val body = TextStyle(fontFamily = SnapFonts.body, fontSize = 15.sp, lineHeight = 22.sp, color = SnapColors.ink)
    val meta = TextStyle(fontFamily = SnapFonts.body, fontSize = 13.sp, lineHeight = 18.sp, color = SnapColors.muted)
    val label = TextStyle(fontFamily = SnapFonts.body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, color = SnapColors.muted)
    val distance = TextStyle(fontFamily = SnapFonts.display, fontSize = 18.sp, lineHeight = 22.sp, color = SnapColors.ink)
}

@Composable
fun SnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = SnapColors.green,
            onPrimary = SnapColors.surface,
            background = SnapColors.paper,
            surface = SnapColors.surface,
            onSurface = SnapColors.ink,
            outline = SnapColors.line,
        ),
        content = content,
    )
}
