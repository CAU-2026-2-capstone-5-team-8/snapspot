/**
 * A small static line chart as SVG, for the simulation and route reports (GitHub renders it in
 * the markdown). Light and dark come from prefers-color-scheme inside the SVG. Palettes checked
 * with the dataviz validator: ORDINAL light #86b6ef/#2a78d6/#104281, dark #184f95/#3987e5/#9ec5f4
 * (ordered series: more = more contrast); CATEGORICAL light #2a78d6/#eb6834/#1baf7a, dark
 * #3987e5/#d95926/#199e70 (distinct series; aqua is 2.74:1 on light, so every line carries a direct
 * label and each report has a table). ORDINAL's slot 3 is the categorical orange, so Held-Karp keeps its color
 * across the route charts.
 */
package com.capstone.snapspot.sim

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * `shade`: palette slot 0..2 (3: ORDINAL's orange). `label`: the direct label at the line's end, instead of "name value".
 * `labelBefore`: put that label left of the last point, ending there (for a line that stops mid-plot and would
 * otherwise label across the lines to its right).
 */
class Series(
    val name: String,
    val shade: Int,
    val values: List<Double>,
    val label: String? = null,
    val labelBefore: Boolean = false,
    /** Estimated values for the x positions after `values`, drawn dashed with hollow markers. */
    val estimate: List<Double> = emptyList(),
) {
    val points: List<Double> get() = values + estimate
}

/** A dashed horizontal guide at `value` (a threshold), labeled at the plot's right edge (or left, `atStart`). */
class RefLine(val value: Double, val label: String, val atStart: Boolean = false)

/** A dashed vertical divider between x index `after` and `after + 1`, with a label on each side. */
class Split(val after: Int, val left: String, val right: String)

enum class Palette(val light: List<String>, val dark: List<String>) {
    ORDINAL(listOf("#86b6ef", "#2a78d6", "#104281", "#eb6834"), listOf("#184f95", "#3987e5", "#9ec5f4", "#d95926")),
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
private fun plainNum(v: Double, percent: Boolean) = when {
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
    /** One line under the title, e.g. how to read the axis. */
    subtitle: String? = null,
    refLine: RefLine? = null,
    split: Split? = null,
    /** Formats values (ticks on a log axis, end labels, tooltips) instead of plain numbers, e.g. as durations. */
    format: ((Double) -> String)? = null,
    /** Room below the lowest tick, in axis units, so a line sitting on that value stays clear of the axis. */
    bottomPad: Double = 0.0,
): String {
    val all = series.flatMap { it.points }
    fun num(v: Double, percent: Boolean) = format?.invoke(v) ?: plainNum(v, percent)
    if (logY) require(all.all { it > 0 }) { "log axis needs positive values" }
    fun tr(v: Double) = if (logY) log10(v) else v
    val step: Double
    var lo: Double
    var hi: Double
    if (logY) {
        step = 1.0
        lo = floor(log10(all.min()))
        hi = ceil(log10(all.max()))
        // Keep the top decade's upper part free for guide labels when the data nearly touches it.
        if (hi - log10(all.max()) < 0.3) hi += 1
    } else {
        step = if (percent) 10.0 else niceStep(all.max())
        lo = if (percent) floor((all.min() - 2) / step) * step else 0.0
        hi = ceil((all.max() + step / 4) / step) * step
        if (percent) { lo = lo.coerceAtLeast(0.0); hi = hi.coerceAtMost(100.0) }
    }
    if (hi <= lo) hi = lo + step
    fun endLabel(s: Series) = s.label ?: "${s.name} ${num(s.points.last(), percent)}"
    // Room on the right for the labels of lines that run to the last x (shorter lines label inside the plot).
    val right = max(MIN_RIGHT, series.filter { it.points.size == xs.size }.maxOfOrNull { (textW(endLabel(it)) + 18).toInt() } ?: 0)
    val top = TOP + if (subtitle != null) 18 else 0
    val legendY = if (subtitle != null) 56 else 38
    val plotW = W - LEFT - right
    val plotH = H - top - BOTTOM
    fun px(i: Int) = LEFT + if (xs.size == 1) plotW / 2.0 else plotW * i / (xs.size - 1).toDouble()
    val base = lo - bottomPad
    fun py(v: Double) = top + plotH * (1 - (tr(v) - base) / (hi - base))
    fun pyRaw(t: Double) = top + plotH * (1 - (t - base) / (hi - base))
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
        subtitle?.let { append("""<text class="t2" x="$LEFT" y="44" font-size="12">${esc(it)}</text>""") }
        // Legend for two or more series, in series order.
        if (series.size >= 2) {
            var lx = LEFT.toDouble()
            for (s in series) {
                append("""<rect class="m${s.shade}" x="${f(lx)}" y="$legendY" width="12" height="3" rx="1.5"/>""")
                append("""<text class="t2" x="${f(lx + 16)}" y="${legendY + 5}" font-size="12">${esc(s.name)}</text>""")
                lx += 30 + textW(s.name)
            }
        }
        // Recessive grid and y ticks (powers of ten on a log axis; every other one labeled when they would crowd).
        val labelEvery = if (logY && hi - lo > 7) 2 else 1
        var t = lo
        while (t <= hi + 1e-9) {
            val y = pyRaw(t)
            val label = if (logY) format?.invoke(10.0.pow(t)) ?: logTick(t.toInt()) else tick(t, step, percent)
            append("""<line class="grid" x1="$LEFT" x2="${LEFT + plotW}" y1="${f(y)}" y2="${f(y)}" stroke-width="1"/>""")
            if (Math.round((t - lo) / step) % labelEvery == 0L) {
                append("""<text class="t2" x="${LEFT - 8}" y="${f(y + 4)}" font-size="11" text-anchor="end">${esc(label)}</text>""")
            }
            t += step
        }
        append("""<line class="axis" x1="$LEFT" x2="${LEFT + plotW}" y1="${f(pyRaw(base))}" y2="${f(pyRaw(base))}" stroke-width="1"/>""")
        append("""<text class="t2" x="${LEFT - 8}" y="${top - 10}" font-size="11" text-anchor="end">${esc(yLabel)}</text>""")
        // Guides sit under the data: a threshold across, a divider down.
        refLine?.let { r ->
            val y = py(r.value)
            append("""<line class="axis" x1="$LEFT" x2="${LEFT + plotW}" y1="${f(y)}" y2="${f(y)}" stroke-width="1" stroke-dasharray="4 4"/>""")
            if (r.atStart) append("""<text class="t2" x="${LEFT + 4}" y="${f(y - 6)}" font-size="11">${esc(r.label)}</text>""")
            else append("""<text class="t2" x="${LEFT + plotW - 4}" y="${f(y - 6)}" font-size="11" text-anchor="end">${esc(r.label)}</text>""")
        }
        split?.let { sp ->
            val x = (px(sp.after) + px(sp.after + 1)) / 2
            append("""<line class="axis" x1="${f(x)}" x2="${f(x)}" y1="$top" y2="${f(pyRaw(base))}" stroke-width="1" stroke-dasharray="4 4"/>""")
            append("""<text class="t2" x="${f(x - 6)}" y="${top + 12}" font-size="11" text-anchor="end">${esc(sp.left)}</text>""")
            append("""<text class="t2" x="${f(x + 6)}" y="${top + 12}" font-size="11">${esc(sp.right)}</text>""")
        }
        // Every other x label when they would crowd.
        val every = if (xs.size > 10) 2 else 1
        xs.forEachIndexed { i, label ->
            if (i % every == 0 || i == xs.size - 1) {
                append("""<text class="t2" x="${f(px(i))}" y="${H - BOTTOM + 20}" font-size="12" text-anchor="middle">${esc(label)}</text>""")
            }
        }
        append("""<text class="t2" x="${LEFT + plotW / 2}" y="${H - 10}" font-size="12" text-anchor="middle">${esc(xLabel)}</text>""")
        // Lines, then markers with a surface ring, then a direct label at the end of each line.
        val ends = series.filter { !it.labelBefore }.map { it to py(it.points.last()) }.sortedBy { it.second }
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
            if (s.estimate.isNotEmpty()) {
                val from = s.values.size - 1
                val est = (listOf(s.values.last()) + s.estimate).mapIndexed { j, value -> "${f(px(from + j))},${f(py(value))}" }.joinToString(" ")
                append("""<polyline class="l${s.shade}" points="$est" fill="none" stroke-width="2" stroke-dasharray="5 4" stroke-linejoin="round"/>""")
            }
            s.values.forEachIndexed { i, value ->
                append("""<circle class="m${s.shade} ring" cx="${f(px(i))}" cy="${f(py(value))}" r="4" stroke-width="2"><title>${esc(s.name)} · ${esc(xs[i])}: ${num(value, percent)}</title></circle>""")
            }
            s.estimate.forEachIndexed { j, value ->
                val i = s.values.size + j
                append("""<circle class="bg l${s.shade}" cx="${f(px(i))}" cy="${f(py(value))}" r="3.5" stroke-width="2"><title>${esc(s.name)} · ${esc(xs[i])}: ${num(value, percent)} (추정)</title></circle>""")
            }
            val lastX = px(s.points.size - 1)
            if (s.labelBefore) {
                append("""<text class="t1" x="${f(lastX - 10)}" y="${f(py(s.points.last()) + 4)}" font-size="12" text-anchor="end">${esc(endLabel(s))}</text>""")
            } else {
                append("""<text class="t1" x="${f(lastX + 10)}" y="${f(labelY.getValue(s) + 4)}" font-size="12">${esc(endLabel(s))}</text>""")
            }
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
