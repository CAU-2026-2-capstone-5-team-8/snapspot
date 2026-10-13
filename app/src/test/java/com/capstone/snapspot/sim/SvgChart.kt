/**
 * A small static line chart as SVG, for the simulation report (GitHub renders it in the markdown).
 * Light and dark come from prefers-color-scheme inside the SVG; series colors are one blue ramp
 * because the series are ordered (more slots = more contrast against the surface). Palette checked
 * with the dataviz validator: light #86b6ef/#2a78d6/#104281, dark #184f95/#3987e5/#9ec5f4 (ordinal, all pass).
 */
package com.capstone.snapspot.sim

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** `shade`: 0 (lightest on light / dimmest on dark) to 2. */
class Series(val name: String, val shade: Int, val values: List<Double>)

private const val W = 640
private const val H = 340
private const val LEFT = 56
private const val RIGHT = 120
private const val TOP = 64
private const val BOTTOM = 52

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun num(v: Double, percent: Boolean) =
    if (percent) String.format(Locale.US, "%.1f%%", v) else String.format(Locale.US, "%.2f", v)

fun lineChart(title: String, xLabel: String, xs: List<String>, yLabel: String, series: List<Series>, percent: Boolean = true): String {
    val all = series.flatMap { it.values }
    val step = if (percent) 10.0 else niceStep(all.max())
    var lo = if (percent) floor((all.min() - 2) / step) * step else 0.0
    var hi = ceil((all.max() + step / 4) / step) * step
    if (percent) { lo = lo.coerceAtLeast(0.0); hi = hi.coerceAtMost(100.0) }
    if (hi <= lo) hi = lo + step
    val plotW = W - LEFT - RIGHT
    val plotH = H - TOP - BOTTOM
    fun px(i: Int) = LEFT + if (xs.size == 1) plotW / 2.0 else plotW * i / (xs.size - 1).toDouble()
    fun py(v: Double) = TOP + plotH * (1 - (v - lo) / (hi - lo))
    fun f(v: Double) = String.format(Locale.US, "%.1f", v)

    return buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $W $H" width="$W" height="$H" role="img" aria-label="${esc(title)}">""")
        append(
            """<style>
.bg{fill:#fcfcfb}.t1{fill:#0b0b0b}.t2{fill:#52514e}.grid{stroke:#e4e3df}.axis{stroke:#9b9a95}
.l0{stroke:#86b6ef}.l1{stroke:#2a78d6}.l2{stroke:#104281}.m0{fill:#86b6ef}.m1{fill:#2a78d6}.m2{fill:#104281}.ring{stroke:#fcfcfb}
@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.t1{fill:#f0efec}.t2{fill:#c3c2b7}.grid{stroke:#383835}.axis{stroke:#6f6e69}
.l0{stroke:#184f95}.l1{stroke:#3987e5}.l2{stroke:#9ec5f4}.m0{fill:#184f95}.m1{fill:#3987e5}.m2{fill:#9ec5f4}.ring{stroke:#1a1a19}}
text{font-family:Pretendard,'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR',sans-serif}
</style>""",
        )
        append("""<rect class="bg" width="$W" height="$H" rx="8"/>""")
        append("""<text class="t1" x="$LEFT" y="26" font-size="15" font-weight="600">${esc(title)}</text>""")
        // Legend for two or more series, in series order.
        if (series.size >= 2) {
            var lx = LEFT.toDouble()
            for (s in series) {
                append("""<rect class="m${s.shade}" x="${f(lx)}" y="38" width="12" height="3" rx="1.5"/>""")
                append("""<text class="t2" x="${f(lx + 16)}" y="43" font-size="12">${esc(s.name)}</text>""")
                lx += 24 + s.name.length * 9
            }
        }
        // Recessive grid and y ticks.
        var v = lo
        while (v <= hi + 1e-9) {
            val y = py(v)
            append("""<line class="grid" x1="$LEFT" x2="${LEFT + plotW}" y1="${f(y)}" y2="${f(y)}" stroke-width="1"/>""")
            append("""<text class="t2" x="${LEFT - 8}" y="${f(y + 4)}" font-size="11" text-anchor="end">${tick(v, step, percent)}</text>""")
            v += step
        }
        append("""<line class="axis" x1="$LEFT" x2="${LEFT + plotW}" y1="${f(py(lo))}" y2="${f(py(lo))}" stroke-width="1"/>""")
        append("""<text class="t2" x="${LEFT - 8}" y="${TOP - 10}" font-size="11" text-anchor="end">${esc(yLabel)}</text>""")
        xs.forEachIndexed { i, label ->
            append("""<text class="t2" x="${f(px(i))}" y="${H - BOTTOM + 20}" font-size="12" text-anchor="middle">${esc(label)}</text>""")
        }
        append("""<text class="t2" x="${LEFT + plotW / 2}" y="${H - 10}" font-size="12" text-anchor="middle">${esc(xLabel)}</text>""")
        // Lines, then markers with a surface ring, then a direct label at the end of each line.
        val ends = series.map { it to py(it.values.last()) }.sortedBy { it.second }
        val labelY = HashMap<Series, Double>()
        var lastY = Double.NEGATIVE_INFINITY
        for ((s, y) in ends) {
            val yy = maxOf(y, lastY + 16)
            labelY[s] = yy
            lastY = yy
        }
        for (s in series) {
            val pts = s.values.mapIndexed { i, value -> "${f(px(i))},${f(py(value))}" }.joinToString(" ")
            append("""<polyline class="l${s.shade}" points="$pts" fill="none" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>""")
            s.values.forEachIndexed { i, value ->
                append("""<circle class="m${s.shade} ring" cx="${f(px(i))}" cy="${f(py(value))}" r="4" stroke-width="2"><title>${esc(s.name)} · ${esc(xs[i])}: ${num(value, percent)}</title></circle>""")
            }
            val endX = px(s.values.size - 1) + 10
            append("""<text class="t1" x="${f(endX)}" y="${f(labelY.getValue(s) + 4)}" font-size="12">${esc(s.name)} ${num(s.values.last(), percent)}</text>""")
        }
        append("</svg>")
    }
}

private fun tick(v: Double, step: Double, percent: Boolean): String = when {
    percent || step >= 1 -> v.toInt().toString()
    step >= 0.1 && (step * 10) % 1.0 == 0.0 -> String.format(Locale.US, "%.1f", v)
    else -> String.format(Locale.US, "%.2f", v)
}

private fun niceStep(max: Double): Double {
    val raw = max / 4
    val mag = Math.pow(10.0, floor(Math.log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}
