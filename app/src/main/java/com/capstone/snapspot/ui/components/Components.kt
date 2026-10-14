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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.image.focusCrop
import com.capstone.snapspot.image.photoOnly
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A saved screenshot, decoded small off the main thread. Cropped to the box around the screenshot's picture
 * (a post's photo, a place page's header), not its middle, which is often text.
 */
@Composable
fun Shot(
    uri: String?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = SnapShapes.small,
) {
    val ctx = LocalContext.current
    val aspect = width.value / height.value
    val image by produceState<ImageBitmap?>(null, uri, aspect) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                ctx.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, opts) }
                    ?.let { focusCrop(it, aspect) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.size(width, height).clip(shape).background(SnapColors.line), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
    }
}

/** Just the picture of a screenshot at full width, as tall as the picture is (clamped to `minHeight..maxHeight`). */
@Composable
fun PhotoShot(uri: String?, minHeight: Dp, maxHeight: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                ctx.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, opts) }
                    ?.let { photoOnly(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val img = image
        val h = if (img == null) minHeight else (maxWidth * (img.height.toFloat() / img.width)).coerceIn(minHeight, maxHeight)
        Box(Modifier.fillMaxWidth().height(h).background(SnapColors.line)) {
            img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
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
        if (img != null) Image(img, contentDescription = "기프티콘 이미지", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        else Text(if (uri == null) "기프티콘 이미지가 없어요" else "", style = SnapType.meta)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 52.dp),
        shape = SnapShapes.card,
        colors = ButtonDefaults.buttonColors(
            containerColor = SnapColors.ink,
            contentColor = SnapColors.surface,
            disabledContainerColor = SnapColors.line,
            disabledContentColor = SnapColors.muted,
        ),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = SnapColors.ink, strokeWidth = 2.dp)
        else Text(label, style = SnapType.button, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.heightIn(min = 52.dp),
        shape = SnapShapes.card,
        border = BorderStroke(1.dp, SnapColors.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = SnapColors.surface, contentColor = SnapColors.ink),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = SnapColors.ink, strokeWidth = 2.dp)
        else Text(label, style = SnapType.button, maxLines = 1)
    }
}

@Composable
fun QuietButton(label: String, onClick: () -> Unit, color: Color = SnapColors.ink2) {
    TextButton(onClick = onClick) { Text(label, style = SnapType.button, color = color) }
}

/** A 44dp tap target with one icon (back, close, menu). */
@Composable
fun IconTap(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = SnapColors.ink)
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = SnapType.meta)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = if (large) SnapType.title else SnapType.body,
            placeholder = placeholder?.let { { Text(it, style = SnapType.meta) } },
            singleLine = true,
            shape = SnapShapes.card,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SnapColors.ink,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = SnapColors.bg,
                unfocusedContainerColor = SnapColors.bg,
                cursorColor = SnapColors.ink,
            ),
        )
    }
}

/** Something the user should fix: a white card with an ink bar on the left and an optional text action. */
@Composable
fun Notice(text: String, action: Pair<String, () -> Unit>? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(SnapShapes.card).background(SnapColors.surface)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(SnapColors.ink))
        Column(Modifier.padding(SnapSpace.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = SnapType.body)
            action?.let { (label, onClick) ->
                Text(label, style = SnapType.button.copy(color = SnapColors.ink), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun <T> Segmented(options: List<T>, value: T, onChange: (T) -> Unit, format: (T) -> String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.line).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (o in options) {
            val on = o == value
            Box(
                Modifier.weight(1f).clip(SnapShapes.small).background(if (on) SnapColors.surface else Color.Transparent)
                    .clickable { onChange(o) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(format(o), style = SnapType.label.copy(color = if (on) SnapColors.ink else SnapColors.ink2, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold))
            }
        }
    }
}

/** White card on the grey background: no border or shadow unless asked. */
@Composable
fun Card(modifier: Modifier = Modifier, border: BorderStroke? = null, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface)
            .then(if (border != null) Modifier.border(border, SnapShapes.card) else Modifier),
    ) { content() }
}

/** Lime chip: "you are near this right now". The one meaning lime has. */
@Composable
fun NearChip(text: String = "지금 근처", modifier: Modifier = Modifier) {
    Text(
        text,
        style = SnapType.meta.copy(fontWeight = FontWeight.Bold, color = SnapColors.limeInk),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(SnapShapes.small).background(SnapColors.lime).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A white chip floating on the map (logo, "알림 12곳", back to the list). */
@Composable
fun FloatingChip(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.shadow(4.dp, SnapShapes.card).clip(SnapShapes.card).background(SnapColors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun SheetHandle() {
    Box(Modifier.padding(vertical = 8.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(SnapColors.muted.copy(alpha = 0.35f)))
}

/** A small line, then the screen's sentence in display type. */
@Composable
fun BigTitle(kicker: String?, title: String, modifier: Modifier = Modifier) = BigTitle(kicker, AnnotatedString(title), modifier)

@Composable
fun BigTitle(kicker: String?, title: AnnotatedString, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        kicker?.let { Text(it, style = SnapType.label) }
        Text(title, style = SnapType.display)
    }
}

@Composable
fun ConfirmDelete(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = SnapType.title) },
        text = { Text(body, style = SnapType.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제", style = SnapType.button, color = SnapColors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", style = SnapType.button, color = SnapColors.ink2) } },
        containerColor = SnapColors.surface,
        shape = SnapShapes.card,
    )
}

@Composable
fun MapUnavailable(reason: String) {
    Column(
        Modifier.fillMaxSize().background(SnapColors.bg).padding(24.dp),
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
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.filter { it.second > 0 }.forEach { (_, n, tone) -> Box(Modifier.weight(n.toFloat()).fillMaxSize().background(tone)) }
            if (total - used > 0) Box(Modifier.weight((total - used).toFloat()).fillMaxSize().background(SnapColors.line))
        }
        parts.forEach { (label, n, tone) -> MeterRow(label, "${n}칸", tone) }
        MeterRow("비어 있음", "${maxOf(total - used, 0)}칸", SnapColors.line, muted = true)
    }
}

@Composable
private fun MeterRow(label: String, count: String, tone: Color, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(tone))
        Text(label, style = SnapType.body.copy(color = if (muted) SnapColors.muted else SnapColors.ink), modifier = Modifier.weight(1f))
        Text(count, style = SnapType.label.copy(color = if (muted) SnapColors.muted else SnapColors.ink, fontWeight = FontWeight.Bold))
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun Gap(w: Dp) = Spacer(Modifier.width(w))
