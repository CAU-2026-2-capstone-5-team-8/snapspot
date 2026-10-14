package com.capstone.snapspot.ui.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.capstone.snapspot.core.WidgetKind

/** "곧 만료": active gifticons, soonest expiry first. */
class ExpiringWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WIDGET_SMALL, WIDGET_MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = loadRepository(context)
        provideContent {
            val state = rememberWidgetState(repo, WidgetKind.EXPIRING)
            WidgetBody(WidgetKind.EXPIRING, state.content, state.hasGifticons)
        }
    }
}

class ExpiringWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ExpiringWidget()
}
