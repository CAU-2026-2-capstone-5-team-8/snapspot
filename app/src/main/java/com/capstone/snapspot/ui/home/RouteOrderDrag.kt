package com.capstone.snapspot.ui.home

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Keep the grabbed row under the finger while its slot changes, including during edge scrolling. */
@Stable
internal class RouteOrderDragState(
    private val list: LazyListState,
    private val selectedIds: () -> List<String>,
    private val move: (String, String) -> Unit,
    private val edge: Float,
) {
    var draggingId by mutableStateOf<String?>(null)
        private set
    private var origin by mutableFloatStateOf(0f)
    private var distance by mutableFloatStateOf(0f)
    private var height = 0

    fun start(id: String) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        origin = item.offset.toFloat()
        distance = 0f
        height = item.size
        draggingId = id
    }

    fun drag(delta: Float) {
        if (draggingId == null) return
        distance += delta
        reorder()
    }

    fun finish() { draggingId = null; distance = 0f }

    fun translation(id: String): Float {
        if (id != draggingId) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return 0f
        return origin + distance - item.offset
    }

    fun reorder() {
        val id = draggingId ?: return
        val selected = selectedIds().toSet()
        val visible = list.layoutInfo.visibleItemsInfo.filter { it.key in selected }
        val center = origin + distance + height / 2f
        // Gaps between cards belong to the nearest slot, never implicitly to the first row.
        val target = visible.minByOrNull { abs(center - (it.offset + it.size / 2f)) }
        val targetId = target?.key as? String ?: return
        if (targetId != id) move(id, targetId)
    }

    suspend fun scrollAtEdge() {
        if (draggingId == null) return
        val info = list.layoutInfo
        val center = origin + distance + height / 2f
        val amount = when {
            center < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - center) / 5f).coerceAtMost(edge / 4f)
            center > info.viewportEndOffset - edge -> ((center - info.viewportEndOffset + edge) / 5f).coerceAtMost(edge / 4f)
            else -> 0f
        }
        if (amount != 0f) { list.scrollBy(amount); reorder() }
    }
}

@Composable
internal fun rememberRouteOrderDragState(
    list: LazyListState, selectedIds: List<String>, onMove: (String, String) -> Unit,
): RouteOrderDragState {
    val ids = rememberUpdatedState(selectedIds)
    val move = rememberUpdatedState(onMove)
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val drag = remember(list, edge) { RouteOrderDragState(list, { ids.value }, { from, to -> move.value(from, to) }, edge) }
    LaunchedEffect(drag.draggingId) {
        while (drag.draggingId != null && isActive) { drag.scrollAtEdge(); delay(16) }
    }
    LaunchedEffect(selectedIds) { if (drag.draggingId != null && drag.draggingId !in selectedIds) drag.finish() }
    return drag
}
