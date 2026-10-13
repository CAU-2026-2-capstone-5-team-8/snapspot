package com.capstone.snapspot.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapFonts
import com.capstone.snapspot.ui.theme.SnapType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved screenshot, decoded small off the main thread. */
@Composable
fun Shot(uri: String?, width: Dp, height: Dp, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                ctx.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier.size(width, height).clip(RoundedCornerShape(10.dp)).background(SnapColors.greenSoft),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
    }
}

/** The screenshot at full width (coupon screen): keeps the barcode readable. */
@Composable
fun LargeShot(uri: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, contentDescription = "쿠폰 이미지", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        else Text(if (uri == null) "쿠폰 이미지가 없어요" else "", style = SnapType.meta)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SnapColors.green, disabledContainerColor = SnapColors.line),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = SnapColors.surface, strokeWidth = 2.dp)
        else Text(label, fontFamily = SnapFonts.body, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SnapColors.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = SnapColors.surface, contentColor = SnapColors.ink),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = SnapColors.green, strokeWidth = 2.dp)
        else Text(label, fontFamily = SnapFonts.body, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun QuietButton(label: String, onClick: () -> Unit, color: Color = SnapColors.muted) {
    TextButton(onClick = onClick) { Text(label, fontFamily = SnapFonts.body, fontWeight = FontWeight.Medium, color = color) }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = SnapType.label)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = if (large) SnapType.title else SnapType.body,
            placeholder = placeholder?.let { { Text(it, style = SnapType.meta) } },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SnapColors.green, unfocusedBorderColor = SnapColors.line,
                focusedContainerColor = SnapColors.surface, unfocusedContainerColor = SnapColors.surface,
            ),
        )
    }
}

/** Amber note with an optional action, for things the user should fix. */
@Composable
fun Notice(text: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.amberSoft).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = SnapType.body.copy(color = SnapColors.amber))
        action?.let { (label, onClick) ->
            Text(label, style = SnapType.body.copy(fontWeight = FontWeight.Bold, color = SnapColors.amber), modifier = Modifier.clickable(onClick = onClick))
        }
    }
}

@Composable
fun <T> Segmented(options: List<T>, value: T, onChange: (T) -> Unit, format: (T) -> String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.surface).border(1.dp, SnapColors.line, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (o in options) {
            val on = o == value
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) SnapColors.ink else Color.Transparent)
                    .clickable { onChange(o) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(format(o), fontFamily = SnapFonts.body, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) SnapColors.surface else SnapColors.ink)
            }
        }
    }
}

/** White card with a hairline border. */
@Composable
fun Card(modifier: Modifier = Modifier, border: BorderStroke = BorderStroke(1.dp, SnapColors.line), content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SnapColors.surface).border(border, RoundedCornerShape(16.dp))) { content() }
}

/** The yellow "near right now" band on top of a card. */
@Composable
fun NearBand(text: String) {
    Text(
        text,
        fontFamily = SnapFonts.display,
        color = SnapColors.ink,
        modifier = Modifier.fillMaxWidth().background(SnapColors.highlight).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun Fact(text: String) {
    Text(
        text,
        style = SnapType.meta.copy(fontSize = SnapType.meta.fontSize * 0.92f),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(SnapColors.paper).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun MapUnavailable(reason: String) {
    Column(
        Modifier.fillMaxSize().background(SnapColors.greenSoft).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("지도를 불러오지 못했어요", style = SnapType.title)
        Spacer(Modifier.height(4.dp))
        Text("카카오 개발자 콘솔에 이 앱의 키 해시가 등록됐는지 확인해 주세요.\n$reason", style = SnapType.meta, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** How the OS geofence slots are shared right now: one segment per group. */
@Composable
fun SlotMeter(total: Int, parts: List<Triple<String, Int, Color>>) {
    val used = parts.sumOf { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(SnapColors.surface), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.filter { it.second > 0 }.forEach { (_, n, tone) -> Box(Modifier.weight(n.toFloat()).fillMaxSize().background(tone)) }
            if (total - used > 0) Box(Modifier.weight((total - used).toFloat()).fillMaxSize().background(SnapColors.paper))
        }
        parts.forEach { (label, n, tone) -> MeterRow(label, "${n}칸", tone) }
        MeterRow("비어 있음", "${maxOf(total - used, 0)}칸", SnapColors.paper, muted = true)
    }
}

@Composable
private fun MeterRow(label: String, count: String, tone: Color, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(tone).border(if (muted) 1.dp else 0.dp, SnapColors.line, RoundedCornerShape(3.dp)))
        Text(label, style = SnapType.body.copy(color = if (muted) SnapColors.muted else SnapColors.ink), modifier = Modifier.weight(1f))
        Text(count, style = SnapType.distance.copy(color = if (muted) SnapColors.muted else SnapColors.ink))
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun Gap(w: Dp) = Spacer(Modifier.width(w))
