package com.capstone.snapspot.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
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
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.capstone.snapspot.R
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.image.focusCrop
import com.capstone.snapspot.ui.components.MapUnavailable
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.kakao.vectormap.GestureType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * A saved place: pin plus its alert radius (none when 0). `number`: position in an open bundle's route,
 * shown instead of the initial. `near`: the user is inside the radius. `couponStop`: a coupon branch on the route.
 */
data class MapPlace(
    val id: String,
    val at: Coords,
    val radiusM: Double,
    /** The place's screenshot (content URI), shown inside its pin. */
    val photo: String?,
    val ended: Boolean,
    val selected: Boolean,
    val number: Int? = null,
    val near: Boolean = false,
    val couponStop: Boolean = false,
    /** A coupon stop's brand slot ([brandTone]). */
    val tone: Int = 0,
)

/** A registered coupon branch: a small square with a gift icon in its brand's color ([brandTone]), no radius. */
data class MapBranch(val id: String, val at: Coords, val urgent: Boolean, val near: Boolean, val tone: Int = 0)

/** Where the camera should go; `seq` changes for every request so the same spot can be re-focused. */
data class MapFocus(val at: Coords, val seq: Long, val zoom: Int = 16)

private val SEOUL = Coords(37.5665, 126.9780)

/**
 * Kakao map with saved places (pin + alert radius), registered branches and the user. Full-bleed by default;
 * with `cornerRadius` > 0 it sits in a rounded card, and since the map renders on a SurfaceView that ignores
 * Compose clipping, the corners are masked with `maskColor`. `padBottom` keeps the camera's center above a
 * bottom sheet covering the map.
 */
@Composable
fun SpotMap(
    places: List<MapPlace>,
    branches: List<MapBranch>,
    here: Coords?,
    focus: MapFocus?,
    modifier: Modifier = Modifier,
    refresh: Pair<Coords, Double>? = null,
    cornerRadius: Dp = 0.dp,
    maskColor: Color = SnapColors.bg,
    interactive: Boolean = true,
    /** An open bundle's walking route, drawn as a line through these points in order. */
    route: List<Coords> = emptyList(),
    padBottom: Dp = 0.dp,
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
    val face = remember { ResourcesCompat.getFont(ctx, R.font.pretendard_bold) ?: Typeface.DEFAULT_BOLD }

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

    // Screenshots for the pins, cropped and decoded once off the main thread; pins without one yet draw a glyph.
    var photos by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    val photoUris = places.mapNotNull { it.photo }.toSet()
    LaunchedEffect(photoUris) {
        val missing = photoUris - photos.keys
        if (missing.isEmpty()) return@LaunchedEffect
        val px = (PHOTO_PIN_DP * density).toInt()
        val loaded = withContext(Dispatchers.IO) { missing.mapNotNull { u -> pinPhoto(ctx, u, px)?.let { u to it } }.toMap() }
        photos = photos + loaded
    }
    LaunchedEffect(map, places, branches, here, refresh, route, photos) {
        val m = map ?: return@LaunchedEffect
        draw(m, places, branches, here, refresh, route, density, face, photos)
    }
    LaunchedEffect(map, interactive) {
        val m = map ?: return@LaunchedEffect
        if (!interactive) {
            listOf(
                GestureType.Pan, GestureType.Zoom, GestureType.Rotate, GestureType.Tilt, GestureType.OneFingerDoubleTap,
                GestureType.TwoFingerSingleTap, GestureType.OneFingerZoom, GestureType.RotateZoom,
            ).forEach { m.setGestureEnable(it, false) }
        }
    }
    LaunchedEffect(map, padBottom) {
        val m = map ?: return@LaunchedEffect
        m.setPadding(0, 0, 0, (padBottom.value * density).toInt())
    }
    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(f.at.latitude, f.at.longitude), f.zoom), CameraAnimation.from(350))
    }

    Box(
        modifier.then(
            if (cornerRadius <= 0.dp) Modifier
            else Modifier.drawWithContent {
                drawContent()
                val r = cornerRadius.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
                }
                drawPath(mask, maskColor)
            },
        ),
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

private fun draw(
    m: KakaoMap, places: List<MapPlace>, branches: List<MapBranch>, here: Coords?, refresh: Pair<Coords, Double>?,
    route: List<Coords>, density: Float, face: Typeface, photos: Map<String, Bitmap>,
) {
    val shapes = m.shapeManager?.layer ?: return
    val labels = m.labelManager?.layer ?: return
    shapes.removeAll()
    labels.removeAll()

    refresh?.let { (c, r) ->
        shapes.addPolygon(PolygonOptions.from(circle(c, r), PolygonStyles.from(REFRESH_AREA.fill.toArgb(), 1.5f * density, REFRESH_AREA.stroke.toArgb())))
    }
    for (p in places) {
        if (p.radiusM <= 0) continue
        val s = radiusStyle(p)
        shapes.addPolygon(PolygonOptions.from(circle(p.at, p.radiusM), PolygonStyles.from(s.fill.toArgb(), 1.5f * density, s.stroke.toArgb())))
    }
    if (route.size >= 2) {
        shapes.addPolyline(PolylineOptions.from(MapPoints.fromLatLng(route.map { latLng(it) }), 3f * density, SnapColors.ink.toArgb()))
    }
    for (b in branches) {
        val bmp = badge(branchPin(b), density, face)
        labels.addLabel(LabelOptions.from(b.id, latLng(b.at)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f))).setTag(b.id).setClickable(true))
    }
    for (p in places) {
        val s = placePin(p)
        val bmp = badge(s, density, face, s.photo?.let { photos[it] })
        labels.addLabel(
            LabelOptions.from(p.id, latLng(p.at)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f)))
                // Numbered route pins sit above the user's dot (rank 20), so standing on stop 1 does not hide its number.
                .setTag(p.id).setClickable(true).setRank(if (p.number != null) 30 else if (p.selected) 10 else 5),
        )
    }
    here?.let {
        val bmp = badge(ME_PIN, density, face)
        labels.addLabel(LabelOptions.from("me", latLng(it)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f))).setRank(20))
    }
}

/** Draws a [PinStyle]: ring, then fill inset by the ring width, then the photo, text or icon inside. */
private fun badge(s: PinStyle, density: Float, face: Typeface, photo: Bitmap? = null): Bitmap {
    val size = (s.sizeDp * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val half = size / 2f
    fun shape(inset: Float) {
        if (s.shape == PinShape.CIRCLE) c.drawCircle(half, half, half - inset, p)
        else {
            val r = (SnapShapes.PIN_CORNER_DP * density - inset).coerceAtLeast(0f)
            c.drawRoundRect(RectF(inset, inset, size - inset, size - inset), r, r, p)
        }
    }
    p.color = s.ring.toArgb()
    shape(0f)
    p.color = s.fill.toArgb()
    shape(s.ringDp * density)
    if (s.text.isNotEmpty()) {
        p.color = s.textColor.toArgb()
        p.textAlign = Paint.Align.CENTER
        p.typeface = face
        p.textSize = size * 0.46f
        c.drawText(s.text, half, half - (p.descent() + p.ascent()) / 2, p)
    }
    if (photo != null) {
        val inset = s.ringDp * density
        val r = half - inset
        val m = Matrix().apply {
            val k = 2 * r / photo.width
            setScale(k, k)
            postTranslate(inset, inset)
        }
        val pp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        pp.shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        if (s.grey) pp.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        c.drawCircle(half, half, r, pp)
    } else when (s.icon) {
        PinIcon.GIFT -> gift(c, half, half, size * 0.56f, s.textColor.toArgb())
        PinIcon.PLACE -> placeGlyph(c, half, half, size * 0.5f, s.textColor.toArgb(), s.fill.toArgb())
        PinIcon.NONE -> Unit
    }
    return bmp
}

/** Photo pins are this big when selected; screenshots are cropped and scaled to it once. */
private const val PHOTO_PIN_DP = 42

/** A square from a screenshot for a pin, `px` wide, centered on the screenshot's picture ([focusCrop]). */
private fun pinPhoto(ctx: Context, uri: String, px: Int): Bitmap? = runCatching {
    val u = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(u).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px * 2) sample *= 2
    val full = ctx.contentResolver.openInputStream(u).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return null
    Bitmap.createScaledBitmap(focusCrop(full, 1f), px, px, true)
}.getOrNull()

/** A map pin (round head with a hole, pointed tail) `w` tall, centered on (cx, cy); `hole` is the fill behind it. */
private fun placeGlyph(c: Canvas, cx: Float, cy: Float, w: Float, color: Int, hole: Int) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val r = w * 0.32f
    val headY = cy - w * 0.14f
    val tail = android.graphics.Path().apply {
        moveTo(cx - r * 0.86f, headY + r * 0.5f)
        lineTo(cx, cy + w * 0.5f)
        lineTo(cx + r * 0.86f, headY + r * 0.5f)
        close()
    }
    c.drawPath(tail, p)
    c.drawCircle(cx, headY, r, p)
    p.color = hole
    c.drawCircle(cx, headY, r * 0.4f, p)
}

/** A gift box outline (lid, body, ribbon, bow) `w` wide, centered on (cx, cy): coupon branches carry it instead of a letter. */
private fun gift(c: Canvas, cx: Float, cy: Float, w: Float, color: Int) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w * 0.12f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val h = w / 2
    c.drawRect(cx - h, cy - w * 0.16f, cx + h, cy + w * 0.04f, p)
    c.drawRect(cx - h * 0.82f, cy + w * 0.04f, cx + h * 0.82f, cy + w * 0.46f, p)
    c.drawLine(cx, cy - w * 0.16f, cx, cy + w * 0.46f, p)
    c.drawCircle(cx - w * 0.15f, cy - w * 0.3f, w * 0.12f, p)
    c.drawCircle(cx + w * 0.15f, cy - w * 0.3f, w * 0.12f, p)
}
