/**
 * A small static line chart as SVG, for the simulation and route reports (GitHub renders it in
 * the markdown). Light and dark come from prefers-color-scheme inside the SVG. Palettes checked
 * with the dataviz validator: ORDINAL light #86b6ef/#2a78d6/#104281, dark #184f95/#3987e5/#9ec5f4
 * (ordered series: more = more contrast); CATEGORICAL light #2a78d6/#eb6834/#1baf7a, dark
 * #3987e5/#d95926/#199e70 (distinct series; aqua is 2.74:1 on light, so every line carries a direct
 * label and each report has a table).
 */
package com.capstone.snapspot.sim

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** `shade`: palette slot 0..2. */
class Series(val name: String, val shade: Int, val values: List<Double>)

enum class Palette(val light: List<String>, val dark: List<String>) {
    ORDINAL(listOf("#86b6ef", "#2a78d6", "#104281"), listOf("#184f95", "#3987e5", "#9ec5f4")),
    CATEGORICAL(listOf("#2a78d6", "#eb6834", "#1baf7a"), listOf("#3987e5", "#d95926", "#199e70")),
}

private const val W = 640
private const val H = 340
private const val LEFT = 56
private const val MIN_RIGHT = 120
private const val TOP = 64
private const val BOTTOM = 52

/** Rough rendered width at 12px: Hangul is about twice as wide as Latin. */
private fun textW(s: String) = s.sumOf { if (it.code < 128) 7.0 else 12.5 }

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun num(v: Double, percent: Boolean) = when {
    percent -> String.format(Locale.US, "%.1f%%", v)
    v >= 10 -> String.format(Locale.US, "%.0f", v)
    v >= 0.1 -> String.format(Locale.US, "%.2f", v)
    else -> String.format(Locale.US, "%.3f", v)
}

/** `.lN` line and `.mN` marker colors for each palette slot. */
private fun seriesCss(colors: List<String>) = colors.indices.joinToString("") { i -> ".l$i{stroke:${colors[i]}}.m$i{fill:${colors[i]}}" }

fun lineChart(
    title: String,
    xLabel: String,
    xs: List<String>,
    yLabel: String,
    series: List<Series>,
    percent: Boolean = true,
    logY: Boolean = false,
    palette: Palette = Palette.ORDINAL,
): String {
    val all = series.flatMap { it.values }
    if (logY) require(all.all { it > 0 }) { "log axis needs positive values" }
    fun tr(v: Double) = if (logY) log10(v) else v
    val step: Double
    var lo: Double
    var hi: Double
    if (logY) {
        step = 1.0
        lo = floor(log10(all.min()))
        hi = ceil(log10(all.max()))
    } else {
        step = if (percent) 10.0 else niceStep(all.max())
        lo = if (percent) floor((all.min() - 2) / step) * step else 0.0
        hi = ceil((all.max() + step / 4) / step) * step
        if (percent) { lo = lo.coerceAtLeast(0.0); hi = hi.coerceAtMost(100.0) }
    }
    if (hi <= lo) hi = lo + step
    // Room on the right for the longest direct label.
    val right = max(MIN_RIGHT, series.maxOf { (textW("${it.name} ${num(it.values.last(), percent)}") + 18).toInt() })
    val plotW = W - LEFT - right
    val plotH = H - TOP - BOTTOM
    fun px(i: Int) = LEFT + if (xs.size == 1) plotW / 2.0 else plotW * i / (xs.size - 1).toDouble()
    fun py(v: Double) = TOP + plotH * (1 - (tr(v) - lo) / (hi - lo))
    fun pyRaw(t: Double) = TOP + plotH * (1 - (t - lo) / (hi - lo))
    fun f(v: Double) = String.format(Locale.US, "%.1f", v)
    val lightCss = seriesCss(palette.light)
    val darkCss = seriesCss(palette.dark)

    return buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $W $H" width="$W" height="$H" role="img" aria-label="${esc(title)}">""")
        append(
            """<style>
.bg{fill:#fcfcfb}.t1{fill:#0b0b0b}.t2{fill:#52514e}.grid{stroke:#e4e3df}.axis{stroke:#9b9a95}
$lightCss.ring{stroke:#fcfcfb}
@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.t1{fill:#f0efec}.t2{fill:#c3c2b7}.grid{stroke:#383835}.axis{stroke:#6f6e69}
$darkCss.ring{stroke:#1a1a19}}
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
                lx += 30 + textW(s.name)
            }
        }
        // Recessive grid and y ticks (powers of ten on a log axis).
        var t = lo
        while (t <= hi + 1e-9) {
            val y = pyRaw(t)
            val label = if (logY) logTick(t.toInt()) else tick(t, step, percent)
            append("""<line class="grid" x1="$LEFT" x2="${LEFT + plotW}" y1="${f(y)}" y2="${f(y)}" stroke-width="1"/>""")
            append("""<text class="t2" x="${LEFT - 8}" y="${f(y + 4)}" font-size="11" text-anchor="end">$label</text>""")
            t += step
        }
        append("""<line class="axis" x1="$LEFT" x2="${LEFT + plotW}" y1="${f(pyRaw(lo))}" y2="${f(pyRaw(lo))}" stroke-width="1"/>""")
        append("""<text class="t2" x="${LEFT - 8}" y="${TOP - 10}" font-size="11" text-anchor="end">${esc(yLabel)}</text>""")
        // Every other x label when they would crowd.
        val every = if (xs.size > 10) 2 else 1
        xs.forEachIndexed { i, label ->
            if (i % every == 0 || i == xs.size - 1) {
                append("""<text class="t2" x="${f(px(i))}" y="${H - BOTTOM + 20}" font-size="12" text-anchor="middle">${esc(label)}</text>""")
            }
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

private fun logTick(p: Int): String = String.format(Locale.US, "%,.${max(0, -p)}f", 10.0.pow(p))

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
