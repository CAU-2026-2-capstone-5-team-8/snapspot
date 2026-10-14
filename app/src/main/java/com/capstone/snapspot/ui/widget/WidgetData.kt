package com.capstone.snapspot.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.WIDGET_LIST_MAX
import com.capstone.snapspot.core.WidgetContent
import com.capstone.snapspot.core.WidgetKind
import com.capstone.snapspot.core.widgetContent
import com.capstone.snapspot.data.Repository

/**
 * The app's shared store, re-read from disk first like the geofence workers do: this may run in a process that has
 * not loaded it yet.
 */
suspend fun loadRepository(ctx: Context): Repository {
    val repo = (ctx.applicationContext as SnapSpotApp).container.repo
    repo.reload()
    return repo
}

/** What a widget draws: its rows, and whether the user has any active gifticon at all (for the empty message). */
data class WidgetState(val content: WidgetContent, val hasGifticons: Boolean)

/**
 * Follows the store from inside the widget's composition. Glance keeps a session open for a while and, while it is
 * open, an update only recomposes it instead of calling `provideGlance` again, so data read once up front would go stale.
 */
@Composable
fun rememberWidgetState(repo: Repository, kind: WidgetKind): WidgetState {
    val coupons by repo.coupons.collectAsState()
    val window by repo.window.collectAsState()
    val now = System.currentTimeMillis()
    val near = window?.branches?.values?.map { it.brandKey }?.toSet().orEmpty()
    val content = widgetContent(coupons, near, now, kind, WIDGET_LIST_MAX)
    val any = if (kind == WidgetKind.EXPIRING) content.total > 0 else widgetContent(coupons, near, now, WidgetKind.EXPIRING, 1).total > 0
    return WidgetState(content, any)
}
