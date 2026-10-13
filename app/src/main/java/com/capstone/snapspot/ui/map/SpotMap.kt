package com.capstone.snapspot.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.ui.components.MapUnavailable
import com.capstone.snapspot.ui.theme.SnapColors
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.shape.MapPoints
import com.kakao.vectormap.shape.PolygonOptions
import com.kakao.vectormap.shape.PolygonStyles
import com.kakao.vectormap.shape.PolylineOptions
import kotlin.math.cos
import kotlin.math.sin

/** A saved place: pin plus its alert radius (none when 0). `number`: position in an open bundle's route, shown instead of the initial. */
data class MapPlace(val id: String, val at: Coords, val radiusM: Double, val initial: String, val ended: Boolean, val selected: Boolean, val number: Int? = null)

/** A registered franchise branch: a small dot, no radius. */
data class MapBranch(val id: String, val at: Coords, val initial: String, val urgent: Boolean, val near: Boolean)

/** Where the camera should go; `seq` changes for every request so the same spot can be re-focused. */
data class MapFocus(val at: Coords, val seq: Long, val zoom: Int = 16)

private val SEOUL = Coords(37.5665, 126.9780)

/**
 * Kakao map with saved places (pin + alert radius circle), registered branches (dots) and the
 * user. Drawn inside a rounded card: the map renders on a SurfaceView that ignores Compose
 * clipping, so the corners are masked with the page color instead.
 */
@Composable
fun SpotMap(
    places: List<MapPlace>,
    branches: List<MapBranch>,
    here: Coords?,
    focus: MapFocus?,
    modifier: Modifier = Modifier,
    refresh: Pair<Coords, Double>? = null,
    cornerRadius: Dp = 24.dp,
    maskColor: Color = SnapColors.paper,
    interactive: Boolean = true,
    /** An open bundle's walking route, drawn as a line through these points in order. */
    route: List<Coords> = emptyList(),
    onPinClick: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    if (!(ctx.applicationContext as SnapSpotApp).mapReady) {
        Box(modifier) { MapUnavailable("지도 SDK를 시작하지 못했어요 (네이티브 앱 키 또는 이 기기의 CPU 지원)") }
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<KakaoMap?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val onPin by rememberUpdatedState(onPinClick)
    val startAt = remember { focus?.at ?: here ?: places.firstOrNull()?.at ?: SEOUL }
    val density = LocalDensity.current.density

    val view = remember {
        MapView(ctx).apply {
            start(
                object : MapLifeCycleCallback() {
                    override fun onMapDestroy() {
                        map = null
                    }

                    override fun onMapError(error: Exception) {
                        failed = error.message ?: error.javaClass.simpleName
                    }
                },
                object : KakaoMapReadyCallback() {
                    override fun onMapReady(kakaoMap: KakaoMap) {
                        kakaoMap.setOnLabelClickListener { _, _, label ->
                            (label.tag as? String)?.let { onPin(it) }
                            true
                        }
                        map = kakaoMap
                    }

                    override fun getPosition(): LatLng = LatLng.from(startAt.latitude, startAt.longitude)
                    override fun getZoomLevel(): Int = 15
                },
            )
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            view.finish()
        }
    }

    LaunchedEffect(map, places, branches, here, refresh, route) {
        val m = map ?: return@LaunchedEffect
        draw(m, places, branches, here, refresh, route, density)
    }
    LaunchedEffect(map, interactive) {
        val m = map ?: return@LaunchedEffect
        if (!interactive) {
            listOf(com.kakao.vectormap.GestureType.Pan, com.kakao.vectormap.GestureType.Zoom, com.kakao.vectormap.GestureType.Rotate,
                com.kakao.vectormap.GestureType.Tilt, com.kakao.vectormap.GestureType.OneFingerDoubleTap,
                com.kakao.vectormap.GestureType.TwoFingerSingleTap, com.kakao.vectormap.GestureType.OneFingerZoom,
                com.kakao.vectormap.GestureType.RotateZoom).forEach { m.setGestureEnable(it, false) }
        }
    }
    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(f.at.latitude, f.at.longitude), f.zoom), CameraAnimation.from(350))
    }

    Box(
        modifier.drawWithContent {
            drawContent()
            val r = cornerRadius.toPx()
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
            }
            drawPath(mask, maskColor)
        },
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (failed != null) MapUnavailable(failed!!)
    }
}

private fun latLng(c: Coords) = LatLng.from(c.latitude, c.longitude)

/** A 64-gon around `center` with real-world radius in meters. */
private fun circle(center: Coords, radiusM: Double): MapPoints {
    val dLat = radiusM / 111_320.0
    val dLng = radiusM / (111_320.0 * cos(Math.toRadians(center.latitude)))
    val pts = (0 until 64).map { i ->
        val a = 2 * Math.PI * i / 64
        LatLng.from(center.latitude + dLat * sin(a), center.longitude + dLng * cos(a))
    }
    return MapPoints.fromLatLng(pts)
}

private fun withAlpha(c: Color, a: Float) = c.copy(alpha = a).toArgb()

private fun draw(m: KakaoMap, places: List<MapPlace>, branches: List<MapBranch>, here: Coords?, refresh: Pair<Coords, Double>?, route: List<Coords>, density: Float) {
    val shapes = m.shapeManager?.layer ?: return
    val labels = m.labelManager?.layer ?: return
    shapes.removeAll()
    labels.removeAll()

    refresh?.let { (c, r) ->
        shapes.addPolygon(PolygonOptions.from(circle(c, r), PolygonStyles.from(withAlpha(SnapColors.highlight, 0.10f), 2f * density, withAlpha(SnapColors.amber, 0.6f))))
    }
    for (p in places) {
        if (p.radiusM <= 0) continue
        val tone = if (p.ended) SnapColors.muted else SnapColors.green
        shapes.addPolygon(
            PolygonOptions.from(circle(p.at, p.radiusM), PolygonStyles.from(withAlpha(tone, if (p.selected) 0.25f else 0.15f), 1.5f * density, withAlpha(tone, 0.9f))),
        )
    }
    if (route.size >= 2) {
        shapes.addPolyline(PolylineOptions.from(MapPoints.fromLatLng(route.map { latLng(it) }), 3f * density, SnapColors.ink.toArgb()))
    }
    for (b in branches) {
        val fill = if (b.near) SnapColors.highlight else SnapColors.surface
        val ring = if (b.urgent) SnapColors.urgent else SnapColors.ink
        val bmp = badge(b.initial, fill.toArgb(), ring.toArgb(), SnapColors.ink.toArgb(), 22, if (b.urgent) 2.5f else 1.5f, density)
        labels.addLabel(LabelOptions.from(b.id, latLng(b.at)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f))).setTag(b.id).setClickable(true))
    }
    for (p in places) {
        val fill = if (p.ended) SnapColors.muted else SnapColors.green
        val bmp = badge(p.number?.toString() ?: p.initial, fill.toArgb(), SnapColors.surface.toArgb(), SnapColors.surface.toArgb(), if (p.selected) 34 else 28, 3f, density)
        labels.addLabel(
            LabelOptions.from(p.id, latLng(p.at)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f)))
                .setTag(p.id).setClickable(true).setRank(if (p.selected) 10 else 5),
        )
    }
    here?.let {
        val bmp = badge("", SnapColors.ink.toArgb(), SnapColors.surface.toArgb(), 0, 16, 3f, density)
        labels.addLabel(LabelOptions.from("me", latLng(it)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f))).setRank(20))
    }
}

/** A round badge with an optional initial, drawn at `sizeDp`. */
private fun badge(text: String, fill: Int, ring: Int, textColor: Int, sizeDp: Int, ringDp: Float, density: Float): Bitmap {
    val size = (sizeDp * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = ring
    c.drawCircle(r, r, r, p)
    p.color = fill
    c.drawCircle(r, r, r - ringDp * density, p)
    if (text.isNotEmpty()) {
        p.color = textColor
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = size * 0.48f
        val y = r - (p.descent() + p.ascent()) / 2
        c.drawText(text, r, y, p)
    }
    return bmp
}
