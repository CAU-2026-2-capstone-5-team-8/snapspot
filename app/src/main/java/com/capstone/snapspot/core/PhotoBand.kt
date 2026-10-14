package com.capstone.snapspot.core

/**
 * Where the picture is in a screenshot. A post's photo, a place page's header or a poster fills whole rows with
 * mid-tone pixels, while the text around it is mostly white (or black) background. Each row is scored by the
 * share of its pixels that are neither near-white nor near-black ([photoRowScore]); the picture is the longest
 * run of rows scoring at least [PHOTO_ROW_MIN], bridging gaps of up to 2% of the height (a caption line, a seam).
 */
const val PHOTO_ROW_MIN = 0.5f

/** Share of mid-tone pixels among `luma` values (0–255) of one row. */
fun photoRowScore(luma: IntArray): Float =
    if (luma.isEmpty()) 0f else luma.count { it in 24..232 }.toFloat() / luma.size

/** Rows `[first, last]` of the picture, or null when no run is at least 15% of the height. */
fun photoBand(scores: FloatArray): IntRange? {
    val gap = maxOf(1, scores.size / 50)
    var best: IntRange? = null
    var start = -1
    var lastHit = -1
    fun close() {
        if (start >= 0 && (best == null || lastHit - start > best!!.last - best!!.first)) best = start..lastHit
        start = -1
    }
    for (i in scores.indices) {
        if (scores[i] >= PHOTO_ROW_MIN) {
            if (start >= 0 && i - lastHit > gap + 1) close()
            if (start < 0) start = i
            lastHit = i
        }
    }
    close()
    return best?.takeIf { it.last - it.first + 1 >= scores.size * 0.15f }
}

/**
 * Top row of a window `window` rows tall, centered on the picture when there is one (else on the screenshot's
 * middle) and kept inside `0..height`.
 */
fun focusTop(band: IntRange?, height: Int, window: Int): Int {
    val center = band?.let { (it.first + it.last) / 2 } ?: (height / 2)
    return (center - window / 2).coerceIn(0, maxOf(0, height - window))
}
