package com.capstone.snapspot.ui.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.capstone.snapspot.core.WidgetKind

/** "근처 지점": only gifticons whose brand has a branch in the alert window. */
class NearbyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WIDGET_SMALL, WIDGET_MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = loadRepository(context)
        provideContent {
            val state = rememberWidgetState(repo, WidgetKind.NEARBY)
            WidgetBody(WidgetKind.NEARBY, state.content, state.hasGifticons)
        }
    }
}

class NearbyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NearbyWidget()
}
