package com.capstone.snapspot.image

import android.graphics.Bitmap
import android.graphics.Color
import com.capstone.snapspot.core.focusTop
import com.capstone.snapspot.core.photoBand
import com.capstone.snapspot.core.photoRowScore
import kotlin.math.roundToInt

/** Rows of the picture inside a screenshot (see [photoBand]), scored on a 64-px-wide copy. */
fun photoBandOf(bmp: Bitmap): IntRange? {
    val w = 64
    val h = (bmp.height * w / bmp.width.toFloat()).roundToInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(bmp, w, h, true)
    val px = IntArray(w)
    val scores = FloatArray(h) { y ->
        small.getPixels(px, 0, w, 0, y, w, 1)
        photoRowScore(IntArray(w) { (Color.red(px[it]) * 299 + Color.green(px[it]) * 587 + Color.blue(px[it]) * 114) / 1000 })
    }
    val band = photoBand(scores) ?: return null
    val k = bmp.height / h.toFloat()
    return (band.first * k).toInt()..minOf(bmp.height - 1, ((band.last + 1) * k).toInt() - 1)
}

/** A `aspect` (width / height) crop of a screenshot, as wide as possible, centered on its picture. */
fun focusCrop(bmp: Bitmap, aspect: Float): Bitmap {
    var cw = bmp.width
    var ch = (cw / aspect).roundToInt()
    if (ch > bmp.height) {
        ch = bmp.height
        cw = (ch * aspect).roundToInt()
    }
    return Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, focusTop(photoBandOf(bmp), bmp.height, ch), cw, ch)
}

/** Just the picture of a screenshot at full width, or the whole screenshot when it has none. */
fun photoOnly(bmp: Bitmap): Bitmap {
    val band = photoBandOf(bmp) ?: return bmp
    return Bitmap.createBitmap(bmp, 0, band.first, bmp.width, band.last - band.first + 1)
}
