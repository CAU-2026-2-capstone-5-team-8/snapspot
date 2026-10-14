package com.capstone.snapspot.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.capstone.snapspot.MainActivity
import com.capstone.snapspot.core.WIDGET_COMPACT_MAX
import com.capstone.snapspot.core.WidgetContent
import com.capstone.snapspot.core.WidgetItem
import com.capstone.snapspot.core.WidgetKind
import com.capstone.snapspot.ui.theme.SnapColors

/** The two sizes a widget draws itself for; the launcher picks the nearest one that fits. */
val WIDGET_SMALL = DpSize(110.dp, 110.dp)
val WIDGET_MEDIUM = DpSize(250.dp, 160.dp)

/** Glance draws RemoteViews, which cannot use the app's Pretendard font or Compose theme; only the colors carry over. */
private fun color(c: Color) = ColorProvider(c)

private fun title(kind: WidgetKind) = if (kind == WidgetKind.EXPIRING) "곧 만료" else "근처 지점"

/** Why the list is empty. The nearby widget tells "no gifticons" from "no branch near the gifticons". */
fun emptyMessage(kind: WidgetKind, hasGifticons: Boolean, compact: Boolean = false) = when {
    kind != WidgetKind.NEARBY || !hasGifticons -> "저장한 기프티콘이 없어요"
    compact -> "근처 지점이 없어요"
    else -> "근처에 쓸 수 있는 곳이 없어요"
}

private fun openCoupon(ctx: Context, id: String): Action =
    actionStartActivity(Intent(Intent.ACTION_VIEW, "snapspot://coupon/$id".toUri()).setClass(ctx, MainActivity::class.java))

@Composable
fun WidgetBody(kind: WidgetKind, content: WidgetContent, hasGifticons: Boolean) {
    val ctx = LocalContext.current
    val compact = LocalSize.current.height < WIDGET_MEDIUM.height
    val shown = if (compact) content.items.take(WIDGET_COMPACT_MAX) else content.items
    Column(
        modifier = GlanceModifier.fillMaxSize().background(color(SnapColors.surface)).cornerRadius(16.dp).padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(Intent(ctx, MainActivity::class.java))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title(kind), style = TextStyle(color = color(SnapColors.ink), fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(6.dp))
        if (shown.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.CenterStart) {
                Text(emptyMessage(kind, hasGifticons, compact), style = TextStyle(color = color(SnapColors.muted), fontSize = 13.sp))
            }
        } else {
            // No count anywhere: a number next to "곧 만료"/"근처 지점" reads as how many are expiring/near. The small widget drops the benefit line to fit two rows.
            shown.forEach { item -> ItemRow(ctx, item, compact) }
        }
    }
}

@Composable
private fun ItemRow(ctx: Context, item: WidgetItem, compact: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 5.dp else 4.dp).clickable(openCoupon(ctx, item.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(item.brand, maxLines = 1, style = TextStyle(color = color(SnapColors.ink), fontSize = 14.sp, fontWeight = FontWeight.Bold))
            if (!compact && item.benefit.isNotBlank()) {
                Text(item.benefit, maxLines = 1, style = TextStyle(color = color(SnapColors.muted), fontSize = 12.sp))
            }
        }
        if (item.nearBranch) {
            Text(
                "근처",
                modifier = GlanceModifier.background(color(SnapColors.lime)).cornerRadius(6.dp).padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(color = color(SnapColors.limeInk), fontSize = 11.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(6.dp))
        }
        item.dDay?.let {
            Text(
                it,
                style = TextStyle(
                    color = color(if (item.urgent) SnapColors.urgent else SnapColors.ink2),
                    fontSize = 12.sp,
                    fontWeight = if (item.urgent) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
    }
}
