package com.capstone.snapspot.ui.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.data.WindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Both widgets, redrawn from the store. Does nothing when none is on a home screen. */
suspend fun updateAllWidgets(ctx: Context) {
    ExpiringWidget().updateAll(ctx)
    NearbyWidget().updateAll(ctx)
}

/**
 * Calls [refresh] whenever what the widgets show may have changed: the gifticons, or which brands have a branch in
 * the alert window. Anything else in the window (its position, counters) is ignored, and a burst of writes
 * (saving several gifticons, a window plan) becomes one refresh. A failing refresh is logged and the next change tries again.
 */
@OptIn(FlowPreview::class)
fun CoroutineScope.refreshWidgetsOn(
    coupons: Flow<List<Coupon>>,
    window: Flow<WindowState?>,
    debounceMs: Long = 500,
    refresh: suspend () -> Unit,
): Job = launch {
    val nearBrands = window.map { w -> w?.branches?.values?.map { it.brandKey }?.toSet().orEmpty() }
    combine(coupons, nearBrands) { c, b -> c to b }
        .distinctUntilChanged()
        .debounce(debounceMs)
        .collect {
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SnapSpot", "widget refresh failed", e)
            }
        }
}
